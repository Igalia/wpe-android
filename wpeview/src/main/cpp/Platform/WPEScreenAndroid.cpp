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

#include "WPEScreenAndroid.h"

#include "Logging.h"
#include "WPEScreenSyncObserverAndroid.h"

struct _WPEScreenAndroid {
    WPEScreen parent;
    WPEScreenSyncObserver* syncObserver;
};

G_DEFINE_FINAL_TYPE(WPEScreenAndroid, wpe_screen_android, WPE_TYPE_SCREEN)

static void wpeScreenAndroidDispose(GObject* object)
{
    auto* screen = WPE_SCREEN_ANDROID(object);
    g_clear_object(&screen->syncObserver);
    G_OBJECT_CLASS(wpe_screen_android_parent_class)->dispose(object);
}

static void wpeScreenAndroidInvalidate(WPEScreen* screen)
{
    UNUSED_PARAM(screen);
}

static WPEScreenSyncObserver* wpeScreenAndroidGetSyncObserver(WPEScreen* screen)
{
    auto* screenAndroid = WPE_SCREEN_ANDROID(screen);
    if (!screenAndroid->syncObserver)
        screenAndroid->syncObserver = wpe_screen_sync_observer_android_new();
    return screenAndroid->syncObserver;
}

static void wpe_screen_android_class_init(WPEScreenAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeScreenAndroidDispose;

    WPEScreenClass* screenClass = WPE_SCREEN_CLASS(klass);
    screenClass->invalidate = wpeScreenAndroidInvalidate;
    screenClass->get_sync_observer = wpeScreenAndroidGetSyncObserver;
}

static void wpe_screen_android_init(WPEScreenAndroid* screen)
{
    UNUSED_PARAM(screen);
}

// The screen of an Android display. Its size, scale and refresh rate come from the display, through WPEScreen.java.
WPEScreen* wpe_screen_android_new(guint32 id)
{
    g_return_val_if_fail(id, nullptr);
    return WPE_SCREEN(g_object_new(WPE_TYPE_SCREEN_ANDROID, "id", id, nullptr));
}
