/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
 *   Author: Adrian Perez de Castro <aperez@igalia.com>
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

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

public class WKActivityObserver implements Application.ActivityLifecycleCallbacks {
    private static final String LOGTAG = "WKActivityObserver";

    @Override
    public void onActivityCreated(@NonNull Activity a, @NonNull Bundle b) {
        Log.d(LOGTAG, "activity created: " + a);
    }

    @Override
    public void onActivityStarted(@NonNull Activity a) {
        Log.d(LOGTAG, "activity started: " + a);
        handleActivityStarted(a);
    }

    @Override
    public void onActivityPaused(@NonNull Activity a) {
        Log.d(LOGTAG, "activity paused: " + a);
    }

    @Override
    public void onActivityResumed(@NonNull Activity a) {
        Log.d(LOGTAG, "activity resumed: " + a);
    }

    @Override
    public void onActivityStopped(@NonNull Activity a) {
        Log.d(LOGTAG, "activity stopped: " + a);
        handleActivityStopped(a);
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity a) {
        Log.d(LOGTAG, "activity destroyed: " + a);
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {
        Log.d(LOGTAG, "activity save state: " + a);
    }

    private static native void handleActivityStarted(@NonNull Activity a);
    private static native void handleActivityStopped(@NonNull Activity a);
}
