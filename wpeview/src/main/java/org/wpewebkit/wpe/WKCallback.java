package org.wpewebkit.wpe;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpeview.WPECallback;

@FunctionalInterface
public interface WKCallback<T> {
    void onResult(T result);

    static @Nullable<T> WKCallback<T> fromWPECallback(@Nullable final WPECallback<T> wpeCallback) {
        return wpeCallback == null ? null : wpeCallback::onResult;
    }

    @SuppressWarnings("unchecked")
    static void onStringResult(@Nullable String result, @NonNull WKCallback callback) {
        callback.onResult(result);
    }
}
