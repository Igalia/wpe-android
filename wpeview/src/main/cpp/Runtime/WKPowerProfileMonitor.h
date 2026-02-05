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

#pragma once

#include <glib.h>

G_BEGIN_DECLS

#define WPE_TYPE_ANDROID_POWER_PROFILE_MONITOR (wpe_android_power_profile_monitor_get_type())
G_DECLARE_FINAL_TYPE(
    WPEAndroidPowerProfileMonitor, wpe_android_power_profile_monitor, WPE, ANDROID_POWER_PROFILE_MONITOR, GObject)

G_END_DECLS

/**
 * WKPowerProfileMonitor implements GPowerProfileMonitor for Android.
 *
 * Aggregates two signals to determine "Low Power Mode":
 * 1. Thermal Status (via NDK AThermalManager, API 30+)
 * 2. Battery Saver Mode (via Java PowerManager broadcast)
 *
 * When either condition indicates power constraints, WebKit's LowPowerModeNotifierGLib
 * sees "power-saver-enabled" as TRUE, causing reduced timer precision, stopped
 * smooth animations, and throttled background tabs.
 */
class WKPowerProfileMonitor {
public:
    static void configureJNIMappings();
    static void registerExtension();
};
