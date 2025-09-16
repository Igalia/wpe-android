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

package org.wpewebkit;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

public class WPEApplication extends Application {
    static final String LOGTAG = "WPEApplication";

    private enum ProcessKind {
        MAIN {
            @Override
            public String[] getLibraryNames() {
                return new String[] {"WPEAndroidRuntime"};
            }
        },
        NETWORK {
            @Override
            public String[] getLibraryNames() {
                return new String[] {"WPEAndroidService"};
            }
        },
        WEBCONTENT {
            @Override
            public String[] getLibraryNames() {
                return new String[] {"gstreamer-1.0", "WPEAndroidService"};
            }
        },
        WEBDRIVER {
            @Override
            public String[] getLibraryNames() {
                return new String[] {"WPEWebDriver", "WPEAndroidService"};
            }
        };

        abstract public String[] getLibraryNames();
    }

    private static final ProcessKind getProcessKind() {
        String name = getProcessName();
        int colonPosition = name.indexOf(':');
        if (colonPosition < 0)
            return ProcessKind.MAIN;

        name = name.substring(colonPosition + 1).replaceAll("[0-9]", "");
        switch (name) {
        case "WPEWebProcess":
            return ProcessKind.WEBCONTENT;
        case "WPENetworkProcess":
            return ProcessKind.NETWORK;
        case "WPEWebDriverProcess":
            return ProcessKind.WEBDRIVER;
        default:
            throw new IllegalStateException("Cannot derive process kind from '" + getProcessName() + "'");
        }
    }

    public static final ProcessKind processKind = getProcessKind();

    static {
        final String libraries[] = processKind.getLibraryNames();
        Log.d(LOGTAG, "Process: " + processKind.toString() + " - Libraries: " + String.join(", ", libraries));
        for (String libraryName : libraries)
            System.loadLibrary(libraryName);
    }

    public WPEApplication() {
        super();
        if (processKind == ProcessKind.MAIN)
            registerActivityLifecycleCallbacks(new org.wpewebkit.wpe.WKActivityObserver());
    }
}
