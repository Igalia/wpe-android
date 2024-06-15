package com.wpe.wpe;

import com.wpe.wpeview.WPECallback;

@FunctionalInterface
public interface WKCallback<T> {
    void onResult(T result);

    static <T> WKCallback<T> fromWPECallback(final WPECallback<T> wpeCallback) {
        return wpeCallback == null ? null : wpeCallback::onResult;
    }

    @SuppressWarnings("unchecked")
    static void onStringResult(WKCallback callback, String result) {
        callback.onResult(result);
    }
}
