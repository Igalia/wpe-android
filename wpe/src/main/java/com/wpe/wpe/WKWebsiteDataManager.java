package com.wpe.wpe;

import android.os.Handler;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumSet;

public class WKWebsiteDataManager {

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
        HstsCache(8),
        Itp(11),
        ServiceWorkerRegistrations(12),
        DomCache(13),
        All(14);

        private final int value;

        WebsiteDataType(int value) { this.value = value; }

        public int getValue() { return value == All.value ? (1 << value) - 1 : (1 << value); }
    }

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    WKWebsiteDataManager(long nativePtr) { this.nativePtr = nativePtr; }

    public void clear(@NonNull EnumSet<WebsiteDataType> websiteDataTypes, @Nullable Callback callback) {
        int flags = websiteDataTypes.stream().map(WebsiteDataType::getValue).reduce(0, (x, y) -> x | y);
        if (flags != 0) {
            nativeClear(nativePtr, flags, new CallbackHolder(callback));
        }
    }

    // WKDataManagerCallback that calls a Callback on its original thread
    @FunctionalInterface
    public interface Callback {
        void onResult(boolean result);
    }

    private static final class CallbackHolder {
        private final Handler handler = new Handler();
        private final WKWebsiteDataManager.Callback callback;

        public CallbackHolder(@Nullable Callback cb) { callback = cb; }

        @Keep
        public void commitResult(boolean result) {
            if (callback != null) {
                handler.post(() -> callback.onResult(result));
            }
        }
    }

    private native void nativeClear(long nativePtr, int typesToClear, CallbackHolder callbackHolder);
}
