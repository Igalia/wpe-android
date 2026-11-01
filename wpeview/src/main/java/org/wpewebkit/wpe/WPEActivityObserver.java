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
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.util.ArrayList;

public class WPEActivityObserver implements Application.ActivityLifecycleCallbacks {
    private static final String LOGTAG = "WPEActivityObserver";

    // The started activities, in the order they started. Android starts an activity before it stops the one it
    // replaces, and several activities are started at once in multi window mode, so the current activity is the last
    // one to start that has not stopped yet.
    private static final ArrayList<Activity> startedActivities = new ArrayList<>();

    // The activity that WebKit uses as the current one, for example to start WebXR sessions, or null if no activity is
    // started. Package-private: read by the tests.
    @UiThread
    static @Nullable Activity getCurrentActivity() {
        return startedActivities.isEmpty() ? null : startedActivities.get(startedActivities.size() - 1);
    }

    @Override
    public void onActivityCreated(@NonNull Activity a, @NonNull Bundle b) {
        Log.d(LOGTAG, "activity created: " + a);
    }

    @Override
    public void onActivityStarted(@NonNull Activity a) {
        Log.d(LOGTAG, "activity started: " + a);
        startedActivities.remove(a);
        startedActivities.add(a);
        setCurrentActivity(a);
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
        boolean wasCurrent = getCurrentActivity() == a;
        startedActivities.remove(a);
        if (wasCurrent)
            setCurrentActivity(getCurrentActivity());
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity a) {
        Log.d(LOGTAG, "activity destroyed: " + a);
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {
        Log.d(LOGTAG, "activity save state: " + a);
    }

    private static native void setCurrentActivity(@Nullable Activity a);
}
