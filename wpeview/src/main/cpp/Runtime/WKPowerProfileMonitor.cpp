/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
 *   Author: maceip
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

#include "WKPowerProfileMonitor.h"

#include "JNI/JNI.h"
#include "Logging.h"

#include <gio/gio.h>

// NDK Thermal API (API level 30+) - always include, use runtime detection
#include <android/thermal.h>

struct _WPEAndroidPowerProfileMonitor {
    GObject parent;

    AThermalManager* thermalManager;

    // Thread-safe flags: NDK thermal callbacks occur on Binder threads,
    // JNI calls may be on UI thread. Using gint with g_atomic_int_* for
    // GLib-compatible atomic operations.
    gint isBatterySaverActive;
    gint isThermalThrottling;
};

enum { PROP_0, PROP_POWER_SAVER_ENABLED };

static WPEAndroidPowerProfileMonitor* s_singleton = nullptr;

static void wpe_android_power_profile_monitor_iface_init(GPowerProfileMonitorInterface*);

G_DEFINE_FINAL_TYPE_WITH_CODE(WPEAndroidPowerProfileMonitor, wpe_android_power_profile_monitor, G_TYPE_OBJECT,
    G_IMPLEMENT_INTERFACE(G_TYPE_POWER_PROFILE_MONITOR, wpe_android_power_profile_monitor_iface_init))

static void scheduleUpdateOnMainThread(WPEAndroidPowerProfileMonitor* self)
{
    g_object_ref(self);
    g_main_context_invoke(
        nullptr,
        +[](gpointer userData) -> gboolean {
            auto* monitor = WPE_ANDROID_POWER_PROFILE_MONITOR(userData);
            bool isPowerSaverEnabled
                = g_atomic_int_get(&monitor->isBatterySaverActive) || g_atomic_int_get(&monitor->isThermalThrottling);
            Logging::logDebug("WPEAndroidPowerProfileMonitor: power-saver-enabled=%s (battery=%s, thermal=%s)",
                isPowerSaverEnabled ? "true" : "false",
                g_atomic_int_get(&monitor->isBatterySaverActive) ? "true" : "false",
                g_atomic_int_get(&monitor->isThermalThrottling) ? "true" : "false");

            g_object_notify(G_OBJECT(monitor), "power-saver-enabled");
            g_object_unref(monitor);
            return G_SOURCE_REMOVE;
        },
        self);
}

static void onThermalStatusChanged(void* data, AThermalStatus status)
{
    auto* self = WPE_ANDROID_POWER_PROFILE_MONITOR(data);
    gint throttling = (status >= ATHERMAL_STATUS_SEVERE) ? TRUE : FALSE;

    Logging::logDebug(
        "WPEAndroidPowerProfileMonitor: thermal status changed to %d, throttling=%s", status, throttling ? "true" : "false");

    if (g_atomic_int_get(&self->isThermalThrottling) != throttling) {
        g_atomic_int_set(&self->isThermalThrottling, throttling);
        scheduleUpdateOnMainThread(self);
    }
}

static void setBatterySaver(gboolean isPowerSaveMode)
{
    if (s_singleton == nullptr) {
        Logging::logDebug("WPEAndroidPowerProfileMonitor: setBatterySaver called before init, ignoring");
        return;
    }

    gint newValue = isPowerSaveMode ? TRUE : FALSE;
    if (g_atomic_int_get(&s_singleton->isBatterySaverActive) != newValue) {
        Logging::logDebug("WPEAndroidPowerProfileMonitor: battery saver changed to %s", newValue ? "true" : "false");
        g_atomic_int_set(&s_singleton->isBatterySaverActive, newValue);
        scheduleUpdateOnMainThread(s_singleton);
    }
}

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEPowerMonitor, "org/wpewebkit/wpe/WPEPowerMonitor");

class JNIWPEPowerMonitorCache final : public JNI::TypedClass<JNIWPEPowerMonitor> {
public:
    JNIWPEPowerMonitorCache()
        : JNI::TypedClass<JNIWPEPowerMonitor>(true)
    {
        registerNativeMethods(
            JNI::StaticNativeMethod<void(jboolean)>("nativeOnPowerSaveModeChanged", nativeOnPowerSaveModeChanged));
    }

private:
    static void nativeOnPowerSaveModeChanged(JNIEnv*, jclass, jboolean isPowerSave)
    {
        bool newVal = (isPowerSave != JNI_FALSE);
        Logging::logDebug("WPEAndroidPowerProfileMonitor: nativeOnPowerSaveModeChanged(%s)", newVal ? "true" : "false");
        setBatterySaver(newVal ? TRUE : FALSE);
    }
};

static const JNIWPEPowerMonitorCache& getJNIWPEPowerMonitorCache()
{
    static const JNIWPEPowerMonitorCache s_singleton;
    return s_singleton;
}

static void wpe_android_power_profile_monitor_iface_init(GPowerProfileMonitorInterface*)
{
    // Interface implemented via "power-saver-enabled" property override
}

static void wpe_android_power_profile_monitor_get_property(GObject* object, guint propId, GValue* value, GParamSpec* pspec)
{
    auto* self = WPE_ANDROID_POWER_PROFILE_MONITOR(object);

    switch (propId) {
    case PROP_POWER_SAVER_ENABLED:
        g_value_set_boolean(value,
            static_cast<gboolean>(
                g_atomic_int_get(&self->isBatterySaverActive) || g_atomic_int_get(&self->isThermalThrottling)));
        break;
    default:
        G_OBJECT_WARN_INVALID_PROPERTY_ID(object, propId, pspec);
        break;
    }
}

static void wpe_android_power_profile_monitor_init(WPEAndroidPowerProfileMonitor* self)
{
    Logging::logDebug("WPEAndroidPowerProfileMonitor: init(%p)", static_cast<void*>(self));

    s_singleton = self;
    self->thermalManager = nullptr;
    g_atomic_int_set(&self->isBatterySaverActive, FALSE);
    g_atomic_int_set(&self->isThermalThrottling, FALSE);

    if (__builtin_available(android 30, *)) {
        self->thermalManager = AThermal_acquireManager();
        if (self->thermalManager != nullptr) {
            Logging::logDebug("WPEAndroidPowerProfileMonitor: thermal manager acquired");

            int result = AThermal_registerThermalStatusListener(self->thermalManager, onThermalStatusChanged, self);
            if (result == 0) {
                AThermalStatus status = AThermal_getCurrentThermalStatus(self->thermalManager);
                g_atomic_int_set(&self->isThermalThrottling, (status >= ATHERMAL_STATUS_SEVERE) ? TRUE : FALSE);
                Logging::logDebug("WPEAndroidPowerProfileMonitor: initial thermal status=%d, throttling=%s",
                    static_cast<int>(status), g_atomic_int_get(&self->isThermalThrottling) ? "true" : "false");
            } else {
                Logging::logError("WPEAndroidPowerProfileMonitor: failed to register thermal listener: %d", result);
            }
        } else {
            Logging::logDebug("WPEAndroidPowerProfileMonitor: thermal manager not available");
        }
    } else {
        Logging::logDebug("WPEAndroidPowerProfileMonitor: thermal API not available (requires Android 11+)");
    }
}

static void wpe_android_power_profile_monitor_dispose(GObject* object)
{
    auto* self = WPE_ANDROID_POWER_PROFILE_MONITOR(object);
    Logging::logDebug("WPEAndroidPowerProfileMonitor: dispose(%p)", static_cast<void*>(object));

    if (__builtin_available(android 30, *)) {
        if (self->thermalManager != nullptr) {
            AThermal_unregisterThermalStatusListener(self->thermalManager, onThermalStatusChanged, self);
            AThermal_releaseManager(self->thermalManager);
            self->thermalManager = nullptr;
        }
    }

    if (s_singleton == self)
        s_singleton = nullptr;

    G_OBJECT_CLASS(wpe_android_power_profile_monitor_parent_class)->dispose(object);
}

static void wpe_android_power_profile_monitor_class_init(WPEAndroidPowerProfileMonitorClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpe_android_power_profile_monitor_dispose;
    objectClass->get_property = wpe_android_power_profile_monitor_get_property;

    g_object_class_override_property(objectClass, PROP_POWER_SAVER_ENABLED, "power-saver-enabled");
}

void WKPowerProfileMonitor::configureJNIMappings()
{
    Logging::logDebug("WKPowerProfileMonitor: configureJNIMappings");
    getJNIWPEPowerMonitorCache();
}

void WKPowerProfileMonitor::registerExtension()
{
    Logging::logDebug("WKPowerProfileMonitor: registerExtension");

    g_type_ensure(WPE_TYPE_ANDROID_POWER_PROFILE_MONITOR);

    if (g_io_extension_point_lookup(G_POWER_PROFILE_MONITOR_EXTENSION_POINT_NAME) == nullptr)
        g_io_extension_point_register(G_POWER_PROFILE_MONITOR_EXTENSION_POINT_NAME);

    g_io_extension_point_implement(
        G_POWER_PROFILE_MONITOR_EXTENSION_POINT_NAME, WPE_TYPE_ANDROID_POWER_PROFILE_MONITOR, "android", 10);

    Logging::logDebug("WKPowerProfileMonitor: registered as GPowerProfileMonitor extension");
}
