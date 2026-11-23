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

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumSet;

public final class WebKitWebsiteDataManager {
    public enum WebsiteDataType {
        MemoryCache(1 << 0),
        DiskCache(1 << 1),
        OfflineApplicationCache(1 << 2),
        SessionStorage(1 << 3),
        LocalStorage(1 << 4),
        IndexedDBDatabases(1 << 5),
        Cookies(1 << 6),
        DeviceIDHashSalt(1 << 7),
        HSTSCache(1 << 8),
        ITP(1 << 9),
        ServiceWorkerRegistrations(1 << 10),
        DOMCache(1 << 11),
        FileSystem(1 << 12),
        All(MemoryCache.value | DiskCache.value | OfflineApplicationCache.value | SessionStorage.value |
            LocalStorage.value | IndexedDBDatabases.value | Cookies.value | DeviceIDHashSalt.value | HSTSCache.value |
            ITP.value | ServiceWorkerRegistrations.value | DOMCache.value | FileSystem.value);

        private final int value;

        WebsiteDataType(int value) { this.value = value; }

        int getValue() { return value; }
    }

    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    WebKitWebsiteDataManager(long nativePtr) { this.mNativePtr = nativePtr; }

    public void clear(@NonNull EnumSet<WebsiteDataType> websiteDataTypes, @Nullable Callback callback) {
        int flags = flagsOf(websiteDataTypes);
        if (!answeredAtOnce(flags, callback))
            nativeClear(mNativePtr, flags, callback != null ? new CallbackHolder(callback) : null);
    }

    /**
     * Removes the data of the given types that WebKit keeps under {@code site}, the name that
     * {@link WebKitNetworkSession#getWebsiteDataName} gives the data of a site. An empty name, which no data has, gets
     * false.
     */
    public void removeForSite(@NonNull EnumSet<WebsiteDataType> websiteDataTypes, @NonNull String site,
                              @Nullable Callback callback) {
        int flags = flagsOf(websiteDataTypes);
        if (site.isEmpty())
            new CallbackHolder(callback).commitResult(false);
        else if (!answeredAtOnce(flags, callback))
            nativeRemoveForSite(mNativePtr, flags, site, callback != null ? new CallbackHolder(callback) : null);
    }

    // Answers a call that needs nothing from WebKit, and tells whether it did: true with no types, false once the
    // session is destroyed.
    private boolean answeredAtOnce(int flags, @Nullable Callback callback) {
        if (flags != 0 && mNativePtr != 0)
            return false;
        new CallbackHolder(callback).commitResult(flags == 0);
        return true;
    }

    private static int flagsOf(@NonNull EnumSet<WebsiteDataType> websiteDataTypes) {
        int flags = 0;
        for (WebsiteDataType type : websiteDataTypes) {
            flags |= type.getValue();
        }
        return flags;
    }

    @FunctionalInterface
    public interface Callback {
        void onResult(boolean result);
    }

    static final class CallbackHolder {
        private final Callback callback;

        CallbackHolder(@Nullable Callback callback) { this.callback = callback; }

        // Also for a result known at once, which the caller gets only once it returned, as one from WebKit.
        @Keep
        void commitResult(boolean result) {
            if (callback != null)
                MainLooperDispatcher.postLater(() -> callback.onResult(result));
        }
    }

    /** Clears the icon database: the icons, and the addresses of their pages. */
    public void clearFavicons() {
        if (mNativePtr != 0)
            nativeClearFavicons(mNativePtr);
    }

    void invalidate() { mNativePtr = 0; }

    private native void nativeRemoveForSite(long nativePtr, int typesToRemove, @NonNull String site,
                                            @Nullable CallbackHolder callbackHolder);
    private native void nativeClear(long nativePtr, int typesToClear, @Nullable CallbackHolder callbackHolder);
    private native void nativeClearFavicons(long nativePtr);
}
