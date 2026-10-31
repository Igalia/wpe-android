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

package org.wpewebkit.wpe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Android 17's local network permission, in the app's process. The network processes tell it when Android blocks one
 * of their connections because the app does not have the permission (see LocalNetwork.cpp).
 */
public final class LocalNetworkAccess {
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    // How long a blocked host is remembered. The network process ends a blocked connection right after telling, so the
    // load that used it fails within moments.
    private static final long BLOCKED_HOST_LIFETIME_MS = 10_000;

    private static final LocalNetworkAccess instance = new LocalNetworkAccess();

    public static @NonNull LocalNetworkAccess getInstance() { return instance; }

    // The hosts of the connections Android blocked, with the time, from SystemClock.elapsedRealtime().
    private final Map<String, Long> blockedHosts = new HashMap<>();

    private LocalNetworkAccess() {}

    /**
     * Whether Android blocked a connection to this host just now, and the app still does not have the permission. For
     * the load that fails because of it. Once the app has the permission, a failure has another reason.
     */
    public boolean isBlocked(@NonNull Context context, @NonNull String host) {
        synchronized (this) {
            removeOldBlockedHosts();
            if (!blockedHosts.containsKey(normalizeHost(host)))
                return false;
        }
        return context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    /** Called from the binder thread when Android blocked a connection of a network process to the local network. */
    public synchronized void onBlocked(@NonNull String host) {
        removeOldBlockedHosts();
        blockedHosts.put(normalizeHost(host), SystemClock.elapsedRealtime());
    }

    private void removeOldBlockedHosts() {
        long now = SystemClock.elapsedRealtime();
        blockedHosts.values().removeIf(time -> now - time > BLOCKED_HOST_LIFETIME_MS);
    }

    // Host names in any case, and IPv6 addresses with or without brackets, as in addresses.
    private static @NonNull String normalizeHost(@NonNull String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]"))
            normalized = normalized.substring(1, normalized.length() - 1);
        return normalized;
    }
}
