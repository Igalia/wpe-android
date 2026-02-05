/**
 * Copyright (C) 2025 maceip
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

#include "WPEAndroidPowerProfileMonitor.h"

#include "JNI/JNI.h"
#include "Logging.h"

#include <atomic>
#include <gio/gio.h>

// NDK Thermal API (API level 30+) - always include, use runtime detection
#include <android/thermal.h>

/**
 * WPEAndroidPowerProfileMonitor implements GPowerProfileMonitor for Android.
 *
 * Aggregates two signals to determine "Low Power Mode":
 * 1. Thermal Status (via NDK AThermalManager on API 30+)
 * 2. Battery Saver Mode (via Java PowerManager broadcast)
 *
 * When either thermal throttling is active (SEVERE or higher) or Battery Saver
 * mode is enabled, the monitor reports power-saver-enabled=TRUE to WebKit.
 */
struct _WPEAndroidPowerMonitor {
    GObject parentInstance;

    AThermalManager* thermalManager;

    // Thread-safe flags: NDK thermal callbacks occur on Binder threads,
    // JNI calls may be on UI thread
    std::atomic<bool> isBatterySaverActive;
    std::atomic<bool> isThermalThrottling;
};

enum { PROP_0, PROP_POWER_SAVER_ENABLED, N_PROPERTIES };

static WPEAndroidPowerMonitor* s_singleton = nullptr;

static void wpeAndroidPowerProfileMonitorIfaceInit(GPowerProfileMonitorInterface* iface);

G_DEFINE_FINAL_TYPE_WITH_CODE(WPEAndroidPowerMonitor, wpe_android_power_profile_monitor, G_TYPE_OBJECT,
    G_IMPLEMENT_INTERFACE(G_TYPE_POWER_PROFILE_MONITOR, wpeAndroidPowerProfileMonitorIfaceInit))

static void updatePowerStateOnMainThread(WPEAndroidPowerMonitor* self)
{
    bool isPowerSaverEnabled = self->isBatterySaverActive.load() || self->isThermalThrottling.load();
    Logging::logDebug("WPEAndroidPowerProfileMonitor: power-saver-enabled=%s (battery=%s, thermal=%s)",
        isPowerSaverEnabled ? "true" : "false", self->isBatterySaverActive.load() ? "true" : "false",
        self->isThermalThrottling.load() ? "true" : "false");

    g_object_notify(G_OBJECT(self), "power-saver-enabled");
}

// AThermalStatus values:
//   ATHERMAL_STATUS_NONE = 0, LIGHT = 1, MODERATE = 2, SEVERE = 3,
//   CRITICAL = 4, EMERGENCY = 5, SHUTDOWN = 6
static void onThermalStatusChanged(void* data, AThermalStatus status)
{
    auto* self = WPE_ANDROID_POWER_PROFILE_MONITOR(data);
    bool throttling = (status >= ATHERMAL_STATUS_SEVERE);

    Logging::logDebug("WPEAndroidPowerProfileMonitor::onThermalStatusChanged(%d, throttling=%s)",
        static_cast<int>(status), throttling ? "true" : "false");

    if (self->isThermalThrottling.load() != throttling) {
        self->isThermalThrottling.store(throttling);
        g_main_context_invoke(
            nullptr,
            +[](gpointer userData) -> gboolean {
                updatePowerStateOnMainThread(WPE_ANDROID_POWER_PROFILE_MONITOR(userData));
                return G_SOURCE_REMOVE;
            },
            self);
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
        Logging::logDebug("WPEAndroidPowerProfileMonitor::nativeOnPowerSaveModeChanged(%s)", newVal ? "true" : "false");
        wpe_android_power_profile_monitor_set_battery_saver(newVal ? TRUE : FALSE);
    }
};

static const JNIWPEPowerMonitorCache& getJNIWPEPowerMonitorCache()
{
    static const JNIWPEPowerMonitorCache s_singleton;
    return s_singleton;
}

static void wpeAndroidPowerProfileMonitorIfaceInit(GPowerProfileMonitorInterface* iface)
{
    // Interface implemented via "power-saver-enabled" property override
    (void)iface;
}

static void wpeAndroidPowerProfileMonitorGetProperty(GObject* object, guint propId, GValue* value, GParamSpec* pspec)
{
    auto* self = WPE_ANDROID_POWER_PROFILE_MONITOR(object);

    switch (propId) {
    case PROP_POWER_SAVER_ENABLED:
        g_value_set_boolean(
            value, static_cast<gboolean>(self->isBatterySaverActive.load() || self->isThermalThrottling.load()));
        break;
    default:
        G_OBJECT_WARN_INVALID_PROPERTY_ID(object, propId, pspec);
        break;
    }
}

static void wpe_android_power_profile_monitor_init(WPEAndroidPowerMonitor* self)
{
    Logging::logDebug("WPEAndroidPowerProfileMonitor::init(%p)", static_cast<void*>(self));

    s_singleton = self;
    self->thermalManager = nullptr;
    self->isBatterySaverActive.store(false);
    self->isThermalThrottling.store(false);

    if (__builtin_available(android 30, *)) {
        self->thermalManager = AThermal_acquireManager();
        if (self->thermalManager != nullptr) {
            Logging::logDebug("WPEAndroidPowerProfileMonitor: Thermal manager acquired");

            int result = AThermal_registerThermalStatusListener(self->thermalManager, onThermalStatusChanged, self);
            if (result == 0) {
                AThermalStatus status = AThermal_getCurrentThermalStatus(self->thermalManager);
                self->isThermalThrottling.store(status >= ATHERMAL_STATUS_SEVERE);
                Logging::logDebug("WPEAndroidPowerProfileMonitor: Initial thermal status=%d, throttling=%s",
                    static_cast<int>(status), self->isThermalThrottling.load() ? "true" : "false");
            } else {
                Logging::logError("WPEAndroidPowerProfileMonitor: Failed to register thermal listener: %d", result);
            }
        } else {
            Logging::logDebug("WPEAndroidPowerProfileMonitor: Thermal manager not available");
        }
    } else {
        Logging::logDebug("WPEAndroidPowerProfileMonitor: Thermal API not available (requires Android 11+)");
    }
}

static void wpeAndroidPowerProfileMonitorDispose(GObject* object)
{
    auto* self = WPE_ANDROID_POWER_PROFILE_MONITOR(object);
    Logging::logDebug("WPEAndroidPowerProfileMonitor::dispose(%p)", static_cast<void*>(object));

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

static void wpe_android_power_profile_monitor_class_init(WPEAndroidPowerMonitorClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeAndroidPowerProfileMonitorDispose;
    objectClass->get_property = wpeAndroidPowerProfileMonitorGetProperty;

    g_object_class_override_property(objectClass, PROP_POWER_SAVER_ENABLED, "power-saver-enabled");
}

void wpe_android_power_profile_monitor_set_battery_saver(gboolean isPowerSaveMode)
{
    if (s_singleton == nullptr) {
        Logging::logDebug("WPEAndroidPowerProfileMonitor::set_battery_saver called before init, ignoring");
        return;
    }

    bool newValue = (isPowerSaveMode != FALSE);
    if (s_singleton->isBatterySaverActive.load() != newValue) {
        Logging::logDebug("WPEAndroidPowerProfileMonitor: Battery saver changed to %s", newValue ? "true" : "false");
        s_singleton->isBatterySaverActive.store(newValue);
        g_main_context_invoke(
            nullptr,
            +[](gpointer userData) -> gboolean {
                updatePowerStateOnMainThread(WPE_ANDROID_POWER_PROFILE_MONITOR(userData));
                return G_SOURCE_REMOVE;
            },
            s_singleton);
    }
}

void wpe_android_power_profile_monitor_set_thermal_throttling(gboolean isThermalThrottling)
{
    if (s_singleton == nullptr) {
        Logging::logDebug("WPEAndroidPowerProfileMonitor::set_thermal_throttling called before init, ignoring");
        return;
    }

    bool newValue = (isThermalThrottling != FALSE);
    if (s_singleton->isThermalThrottling.load() != newValue) {
        Logging::logDebug(
            "WPEAndroidPowerProfileMonitor: Thermal throttling changed to %s", newValue ? "true" : "false");
        s_singleton->isThermalThrottling.store(newValue);
        g_main_context_invoke(
            nullptr,
            +[](gpointer userData) -> gboolean {
                updatePowerStateOnMainThread(WPE_ANDROID_POWER_PROFILE_MONITOR(userData));
                return G_SOURCE_REMOVE;
            },
            s_singleton);
    }
}

void WPEAndroidPowerProfileMonitor::configureJNIMappings()
{
    Logging::logDebug("WPEAndroidPowerProfileMonitor::configureJNIMappings()");
    getJNIWPEPowerMonitorCache();
}

void WPEAndroidPowerProfileMonitor::registerExtension()
{
    Logging::logDebug("WPEAndroidPowerProfileMonitor::registerExtension()");

    g_type_ensure(WPE_TYPE_ANDROID_POWER_PROFILE_MONITOR);

    GIOExtensionPoint* extensionPoint = g_io_extension_point_lookup(G_POWER_PROFILE_MONITOR_EXTENSION_POINT_NAME);
    if (extensionPoint == nullptr)
        extensionPoint = g_io_extension_point_register(G_POWER_PROFILE_MONITOR_EXTENSION_POINT_NAME);

    g_io_extension_point_implement(
        G_POWER_PROFILE_MONITOR_EXTENSION_POINT_NAME, WPE_TYPE_ANDROID_POWER_PROFILE_MONITOR, "android", 10);

    Logging::logDebug("WPEAndroidPowerProfileMonitor: Registered as GPowerProfileMonitor extension");
}
