/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "WPEScreenSyncObserverAndroid.h"

#include "Logging.h"

#include <android/choreographer.h>
#include <android/looper.h>
#include <glib.h>
#include <pthread.h>
#include <utility>
#include <vector>

struct _WPEScreenSyncObserverAndroid {
    WPEScreenSyncObserver parent;
    GMutex mutex;
    bool active {false};
    // A frame callback is posted, or queued to be posted, and holds a reference to the observer.
    bool callbackPending {false};
};

G_DEFINE_FINAL_TYPE(WPEScreenSyncObserverAndroid, wpe_screen_sync_observer_android, WPE_TYPE_SCREEN_SYNC_OBSERVER)

namespace {

// Runs the frame callbacks of all the observers. It never exits: the looper and AChoreographer of a thread are
// not freed when the thread exits.
struct FrameCallbackThread {
    GMutex mutex;
    GCond condition;
    bool starting {true};
    ALooper* looper {nullptr};
    std::vector<WPEScreenSyncObserverAndroid*> observersToPost;
};

// On the frame callback thread, when a callback ran: whether the observer is still active and needs another
// callback; if not, the reference the callback held is released.
bool continueFrameCallbacksOrRelease(WPEScreenSyncObserverAndroid* self)
{
    {
        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
        if (self->active)
            return true;
        self->callbackPending = false;
    }
    g_object_unref(self);
    return false;
}

void frameCallback(int64_t frameTimeNanos, void* data)
{
    UNUSED_PARAM(frameTimeNanos);
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(data);
    if (!continueFrameCallbacksOrRelease(self))
        return;

    // Without the mutex: the sync callbacks can stop or start the observer.
    WPE_SCREEN_SYNC_OBSERVER_CLASS(wpe_screen_sync_observer_android_parent_class)->sync(WPE_SCREEN_SYNC_OBSERVER(self));

    if (continueFrameCallbacksOrRelease(self))
        AChoreographer_postFrameCallback64(AChoreographer_getInstance(), frameCallback, self);
}

void* frameCallbackThreadMain(void* data)
{
    auto& thread = *static_cast<FrameCallbackThread*>(data);
    pthread_setname_np(pthread_self(), "WPEScreenSync");
    auto* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    auto* choreographer = AChoreographer_getInstance();

    if (!choreographer)
        Logging::logError("WPEScreenSyncObserverAndroid: no AChoreographer, WebKit will use a timer for screen sync");

    g_mutex_lock(&thread.mutex);
    thread.looper = choreographer ? looper : nullptr;
    thread.starting = false;
    g_cond_broadcast(&thread.condition);
    if (!thread.looper) {
        g_mutex_unlock(&thread.mutex);
        return nullptr;
    }

    while (true) {
        auto observers = std::exchange(thread.observersToPost, {});
        g_mutex_unlock(&thread.mutex);
        for (auto* observer : observers)
            AChoreographer_postFrameCallback64(choreographer, frameCallback, observer);
        ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
        g_mutex_lock(&thread.mutex);
    }
}

// Started the first time it is needed; null if it could not start.
FrameCallbackThread* frameCallbackThread()
{
    static FrameCallbackThread* const thread = []() -> FrameCallbackThread* {
        auto* newThread = new FrameCallbackThread;
        g_mutex_init(&newThread->mutex);
        g_cond_init(&newThread->condition);
        pthread_t id {};
        if (int error = pthread_create(&id, nullptr, frameCallbackThreadMain, newThread)) {
            Logging::logError(
                "WPEScreenSyncObserverAndroid: cannot create the frame callback thread (error %d), WebKit will use a "
                "timer for screen sync",
                error);
            return nullptr;
        }
        pthread_detach(id);

        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&newThread->mutex);
        while (newThread->starting)
            g_cond_wait(&newThread->condition, &newThread->mutex);
        return newThread->looper ? newThread : nullptr;
    }();
    return thread;
}

// The callback holds a reference to the observer until it is not posted again.
void postFrameCallback(WPEScreenSyncObserverAndroid* observer)
{
    auto* thread = frameCallbackThread();
    {
        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&thread->mutex);
        thread->observersToPost.push_back(static_cast<WPEScreenSyncObserverAndroid*>(g_object_ref(observer)));
    }
    ALooper_wake(thread->looper);
}

} // namespace

static void wpeScreenSyncObserverAndroidStart(WPEScreenSyncObserver* observer)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(observer);
    {
        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
        if (self->active)
            return;
        self->active = true;
        // A callback still pending from before the last stop() carries on.
        if (self->callbackPending)
            return;
        self->callbackPending = true;
    }
    postFrameCallback(self);
}

static void wpeScreenSyncObserverAndroidStop(WPEScreenSyncObserver* observer)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(observer);
    // This runs from the frame callback too: the pending callback finds the observer stopped and releases it.
    g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
    self->active = false;
}

static void wpeScreenSyncObserverAndroidFinalize(GObject* object)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(object);
    g_mutex_clear(&self->mutex);
    G_OBJECT_CLASS(wpe_screen_sync_observer_android_parent_class)->finalize(object);
}

static void wpe_screen_sync_observer_android_class_init(WPEScreenSyncObserverAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->finalize = wpeScreenSyncObserverAndroidFinalize;

    WPEScreenSyncObserverClass* observerClass = WPE_SCREEN_SYNC_OBSERVER_CLASS(klass);
    observerClass->start = wpeScreenSyncObserverAndroidStart;
    observerClass->stop = wpeScreenSyncObserverAndroidStop;
}

static void wpe_screen_sync_observer_android_init(WPEScreenSyncObserverAndroid* self)
{
    g_mutex_init(&self->mutex);
}

WPEScreenSyncObserver* wpe_screen_sync_observer_android_new(void)
{
    // Without a sync observer WebKit falls back to a timer.
    if (!frameCallbackThread())
        return nullptr;
    return WPE_SCREEN_SYNC_OBSERVER(g_object_new(WPE_TYPE_SCREEN_SYNC_OBSERVER_ANDROID, nullptr));
}
