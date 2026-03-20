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

package org.wpewebkit.wpe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Monitors Battery Saver mode and notifies the native WebKit layer via JNI.
 * Combined with thermal throttling from NDK AThermalManager, this enables WebKit
 * to reduce resource usage (animations, timer precision, background tabs) when needed.
 */
public final class WPEPowerMonitor {
    private static final String LOGTAG = "WPEPowerMonitor";

    private static native void nativeOnPowerSaveModeChanged(boolean isPowerSaveMode);

    private final Context mContext;
    private final BroadcastReceiver mReceiver;
    private boolean mIsRegistered = false;

    public WPEPowerMonitor(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                    notifyState();
                }
            }
        };
    }

    public void start() {
        if (mIsRegistered) {
            Log.d(LOGTAG, "Power monitor already started");
            return;
        }

        Log.d(LOGTAG, "Starting power monitor");

        IntentFilter filter = new IntentFilter();
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(mReceiver, filter);
        }

        mIsRegistered = true;
        notifyState();
    }

    public void stop() {
        if (!mIsRegistered) {
            Log.d(LOGTAG, "Power monitor already stopped");
            return;
        }

        Log.d(LOGTAG, "Stopping power monitor");

        try {
            mContext.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(LOGTAG, "Receiver was not registered: " + e.getMessage());
        }

        mIsRegistered = false;
    }

    private void notifyState() {
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        boolean isPowerSave = (pm != null) && pm.isPowerSaveMode();

        Log.d(LOGTAG, "Power save mode: " + isPowerSave);

        try {
            nativeOnPowerSaveModeChanged(isPowerSave);
        } catch (UnsatisfiedLinkError e) {
            Log.e(LOGTAG, "Native method not available: " + e.getMessage());
        }
    }

    public boolean isPowerSaveMode() {
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        return (pm != null) && pm.isPowerSaveMode();
    }
}
