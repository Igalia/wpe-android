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

#include <android/choreographer.h>
#include <android/looper.h>
#include <condition_variable>
#include <mutex>
#include <pthread.h>

struct _WPEScreenSyncObserverAndroid {
    WPEScreenSyncObserver parent;
    pthread_t thread {};
    std::mutex mutex;
    std::condition_variable condition;
    bool active {false};
    bool initialized {false};
    bool initFailed {false};
    ALooper* looper {nullptr};
    AChoreographer* choreographer {nullptr};
};

G_DEFINE_FINAL_TYPE(WPEScreenSyncObserverAndroid, wpe_screen_sync_observer_android, WPE_TYPE_SCREEN_SYNC_OBSERVER)

static void choreographerCallback(int64_t, void* data)
{
    auto* observer = WPE_SCREEN_SYNC_OBSERVER_ANDROID(data);
    WPE_SCREEN_SYNC_OBSERVER_CLASS(wpe_screen_sync_observer_android_parent_class)
        ->sync(WPE_SCREEN_SYNC_OBSERVER(observer));
    std::lock_guard<std::mutex> lock(observer->mutex);
    bool active = observer->active;
    auto* choreographer = observer->choreographer;

    if (active && choreographer)
        AChoreographer_postFrameCallback64(choreographer, choreographerCallback, observer);
}

static void* observerThreadMain(void* data)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(data);

    auto* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    auto* choreographer = AChoreographer_getInstance();

    std::unique_lock<std::mutex> lock(self->mutex);
    if (!looper || !choreographer) {
        self->initFailed = true;
        self->initialized = true;
        self->condition.notify_all();
        lock.unlock();
        g_object_unref(self);
        return nullptr;
    }

    self->looper = looper;
    ALooper_acquire(self->looper);
    self->choreographer = choreographer;
    self->initialized = true;
    self->condition.notify_all();
    bool active = self->active;
    lock.unlock();

    if (active)
        AChoreographer_postFrameCallback64(self->choreographer, choreographerCallback, self);

    while (true) {
        {
            std::lock_guard<std::mutex> lock(self->mutex);
            active = self->active;
        }
        if (!active)
            break;
        ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
    }

    std::lock_guard<std::mutex> lockForExit(self->mutex);
    self->choreographer = nullptr;
    if (self->looper) {
        ALooper_release(self->looper);
        self->looper = nullptr;
    }

    g_object_unref(self);
    return nullptr;
}

static void waitUntilObserverThreadInitialized(WPEScreenSyncObserverAndroid* self)
{
    std::unique_lock<std::mutex> lock(self->mutex);
    self->condition.wait(lock, [self] { return self->initialized; });
}

static void wpeScreenSyncObserverAndroidStart(WPEScreenSyncObserver* observer)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(observer);

    {
        std::lock_guard<std::mutex> lock(self->mutex);
        if (self->active)
            return;

        self->active = true;
        self->initialized = false;
        self->initFailed = false;
        self->looper = nullptr;
        self->choreographer = nullptr;
    }

    g_object_ref(self);
    if (pthread_create(&self->thread, nullptr, observerThreadMain, self) != 0) {
        std::lock_guard<std::mutex> lock(self->mutex);
        self->active = false;
        g_object_unref(self);
        return;
    }

    waitUntilObserverThreadInitialized(self);
    if (self->initFailed) {
        {
            std::lock_guard<std::mutex> lock(self->mutex);
            self->active = false;
        }
        pthread_join(self->thread, nullptr);
    }
}

static void wpeScreenSyncObserverAndroidStop(WPEScreenSyncObserver* observer)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(observer);
    ALooper* looper = nullptr;

    {
        std::lock_guard<std::mutex> lock(self->mutex);
        if (!self->active)
            return;
        self->active = false;
        looper = self->looper;
    }

    if (looper)
        ALooper_wake(looper);

    pthread_join(self->thread, nullptr);
}

static void wpeScreenSyncObserverAndroidDispose(GObject* object)
{
    wpeScreenSyncObserverAndroidStop(WPE_SCREEN_SYNC_OBSERVER(object));
    G_OBJECT_CLASS(wpe_screen_sync_observer_android_parent_class)->dispose(object);
}

static void wpeScreenSyncObserverAndroidFinalize(GObject* object)
{
    G_OBJECT_CLASS(wpe_screen_sync_observer_android_parent_class)->finalize(object);
}

static void wpe_screen_sync_observer_android_class_init(WPEScreenSyncObserverAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeScreenSyncObserverAndroidDispose;
    objectClass->finalize = wpeScreenSyncObserverAndroidFinalize;

    WPEScreenSyncObserverClass* observerClass = WPE_SCREEN_SYNC_OBSERVER_CLASS(klass);
    observerClass->start = wpeScreenSyncObserverAndroidStart;
    observerClass->stop = wpeScreenSyncObserverAndroidStop;
}

static void wpe_screen_sync_observer_android_init(WPEScreenSyncObserverAndroid*)
{
}

WPEScreenSyncObserver* wpe_screen_sync_observer_android_new(void)
{
    return WPE_SCREEN_SYNC_OBSERVER(g_object_new(WPE_TYPE_SCREEN_SYNC_OBSERVER_ANDROID, nullptr));
}
