/**
 * Copyright (C) 2026 Igalia S.L. <info@igalia.com>
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

/**
 * WebKitWebsiteDataManager is a borrowed JNI proxy for the native WebKitWebsiteDataManager associated with a
 * WebKitNetworkSession. The native session must outlive this wrapper.
 */
public final class WebKitWebsiteDataManager {
    public enum WebsiteDataType {
        MemoryCache(0),
        DiskCache(1),
        OfflineApplicationCache(2),
        SessionStorage(3),
        LocalStorage(4),
        WebSqlDatabases(5),
        IndexDbDatabases(6),
        PluginData(7),
        Cookies(8),
        DeviceIdHashSalt(9),
        HstsCache(10),
        Itp(11),
        ServiceWorkerRegistrations(12),
        DomCache(13),
        All(14);

        private final int value;

        WebsiteDataType(int value) { this.value = value; }

        int getValue() { return value == All.value ? (1 << value) - 1 : (1 << value); }
    }

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    WebKitWebsiteDataManager(long nativePtr) { this.nativePtr = nativePtr; }

    public void clear(@NonNull EnumSet<WebsiteDataType> websiteDataTypes, @Nullable Callback callback) {
        int flags = websiteDataTypes.stream().map(WebsiteDataType::getValue).reduce(0, (x, y) -> x | y);
        CallbackHolder callbackHolder = callback != null ? new CallbackHolder(callback) : null;
        if (flags == 0)
            return;
        if (nativePtr == 0) {
            if (callbackHolder != null)
                callbackHolder.commitResult(false);
            return;
        }
        nativeClear(nativePtr, flags, callbackHolder);
    }

    @FunctionalInterface
    public interface Callback {
        // Results are delivered on the main looper.
        void onResult(boolean result);
    }

    static final class CallbackHolder {
        private final Callback callback;

        CallbackHolder(@Nullable Callback callback) { this.callback = callback; }

        @Keep
        void commitResult(boolean result) {
            if (callback != null)
                MainLooperDispatcher.post(() -> callback.onResult(result));
        }
    }

    void invalidate() { nativePtr = 0; }

    private native void nativeClear(long nativePtr, int typesToClear, @Nullable CallbackHolder callbackHolder);
}
