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

struct _WPEScreenSyncObserverAndroid {
    WPEScreenSyncObserver parent;
    AChoreographer* choreographer;
    gboolean active;
};

G_DEFINE_FINAL_TYPE(WPEScreenSyncObserverAndroid, wpe_screen_sync_observer_android, WPE_TYPE_SCREEN_SYNC_OBSERVER)

static void choreographerCallback(int64_t, void* data)
{
    auto* observer = WPE_SCREEN_SYNC_OBSERVER_ANDROID(data);
    WPE_SCREEN_SYNC_OBSERVER_CLASS(wpe_screen_sync_observer_android_parent_class)
        ->sync(WPE_SCREEN_SYNC_OBSERVER(observer));
    if (observer->active)
        AChoreographer_postFrameCallback64(observer->choreographer, choreographerCallback, observer);
    else
        g_object_unref(observer);
}

static void wpeScreenSyncObserverAndroidStart(WPEScreenSyncObserver* observer)
{
    auto* self = WPE_SCREEN_SYNC_OBSERVER_ANDROID(observer);
    if (!self->choreographer)
        return;
    self->active = TRUE;
    g_object_ref(self);
    AChoreographer_postFrameCallback64(self->choreographer, choreographerCallback, self);
}

static void wpeScreenSyncObserverAndroidStop(WPEScreenSyncObserver* observer)
{
    WPE_SCREEN_SYNC_OBSERVER_ANDROID(observer)->active = FALSE;
}

static void wpeScreenSyncObserverAndroidDispose(GObject* object)
{
    WPE_SCREEN_SYNC_OBSERVER_ANDROID(object)->active = FALSE;
    G_OBJECT_CLASS(wpe_screen_sync_observer_android_parent_class)->dispose(object);
}

static void wpe_screen_sync_observer_android_class_init(WPEScreenSyncObserverAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeScreenSyncObserverAndroidDispose;

    WPEScreenSyncObserverClass* observerClass = WPE_SCREEN_SYNC_OBSERVER_CLASS(klass);
    observerClass->start = wpeScreenSyncObserverAndroidStart;
    observerClass->stop = wpeScreenSyncObserverAndroidStop;
}

static void wpe_screen_sync_observer_android_init(WPEScreenSyncObserverAndroid* self)
{
    self->choreographer = AChoreographer_getInstance();
}

WPEScreenSyncObserver* wpe_screen_sync_observer_android_new(void)
{
    return WPE_SCREEN_SYNC_OBSERVER(g_object_new(WPE_TYPE_SCREEN_SYNC_OBSERVER_ANDROID, nullptr));
}
