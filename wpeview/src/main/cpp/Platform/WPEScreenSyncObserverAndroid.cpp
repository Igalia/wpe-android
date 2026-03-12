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

struct _WPEScreenSyncObserverAndroid {
    WPEScreenSyncObserver parent;
    pthread_t thread {};
    GMutex mutex;
    GCond condition;
    bool active {false};
    bool initialized {false};
    bool initFailed {false};
    ALooper* looper {nullptr};
    AChoreographer* choreographer {nullptr};
};

G_DEFINE_FINAL_TYPE(WPEScreenSyncObserverAndroid, wpe_screen_sync_observer_android, WPE_TYPE_SCREEN_SYNC_OBSERVER)

static void choreographerCallback(int64_t frameTimeNanos, void* data)
{
    UNUSED_PARAM(frameTimeNanos);
    auto* observer = WPE_SCREEN_SYNC_OBSERVER_ANDROID(data);
    WPE_SCREEN_SYNC_OBSERVER_CLASS(wpe_screen_sync_observer_android_parent_class)
        ->sync(WPE_SCREEN_SYNC_OBSERVER(observer));
    bool active;
    AChoreographer* choreographer;
    {
        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&observer->mutex);
        active = observer->active;
        choreographer = observer->choreographer;
    }

    if (active && choreographer)
        AChoreographer_postFrameCallback64(choreographer, choreographerCallback, observer);
}

static void* observerThreadMain(void* data)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(data);

    auto* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    auto* choreographer = AChoreographer_getInstance();

    bool active;
    bool initFailed;
    {
        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
        if (!looper || !choreographer) {
            self->initFailed = true;
            self->initialized = true;
            g_cond_broadcast(&self->condition);
            initFailed = true;
        } else {
            self->looper = looper;
            ALooper_acquire(self->looper);
            self->choreographer = choreographer;
            self->initialized = true;
            g_cond_broadcast(&self->condition);
            active = self->active;
            initFailed = false;
        }
    }
    if (initFailed) {
        g_object_unref(self);
        return nullptr;
    }

    if (active)
        AChoreographer_postFrameCallback64(self->choreographer, choreographerCallback, self);

    while (true) {
        {
            g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
            active = self->active;
        }
        if (!active)
            break;
        ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
    }

    {
        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
        self->choreographer = nullptr;
        g_clear_pointer(&self->looper, ALooper_release);
    }

    g_object_unref(self);
    return nullptr;
}

static void waitUntilObserverThreadInitialized(WPEScreenSyncObserverAndroid* self)
{
    g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
    while (!self->initialized)
        g_cond_wait(&self->condition, &self->mutex);
}

static void wpeScreenSyncObserverAndroidStart(WPEScreenSyncObserver* observer)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(observer);

    {
        g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
        if (self->active)
            return;
        self->active = true;
        self->initialized = false;
        self->initFailed = false;
        self->looper = nullptr;
        self->choreographer = nullptr;
    }

    // Keep the observer alive for the lifetime of the worker thread.
    g_object_ref(self);
    if (pthread_create(&self->thread, nullptr, observerThreadMain, self) != 0) {
        {
            g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
            self->active = false;
        }
        g_object_unref(self);
        return;
    }

    waitUntilObserverThreadInitialized(self);
    if (self->initFailed) {
        {
            g_autoptr(GMutexLocker) locker = g_mutex_locker_new(&self->mutex);
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
        g_autoptr(GMutexLocker) lock = g_mutex_locker_new(&self->mutex);
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
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(object);
    g_mutex_clear(&self->mutex);
    g_cond_clear(&self->condition);
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

static void wpe_screen_sync_observer_android_init(WPEScreenSyncObserverAndroid* self)
{
    g_mutex_init(&self->mutex);
    g_cond_init(&self->condition);
}

WPEScreenSyncObserver* wpe_screen_sync_observer_android_new(void)
{
    return WPE_SCREEN_SYNC_OBSERVER(g_object_new(WPE_TYPE_SCREEN_SYNC_OBSERVER_ANDROID, nullptr));
}
