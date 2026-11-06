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
import androidx.annotation.UiThread;

/**
 * WebKitDownload is a JNI proxy for a native WebKitDownload whose destination is to be decided. WebKit keeps the
 * download in the network session of the page, which fetches it once. The native download goes away once it ends,
 * which the listener hears of.
 */
@UiThread
public final class WebKitDownload {
    // Mirrors WebKitDownloadError, with 0 for any other error.
    public static final int ERROR_UNKNOWN = 0;
    public static final int ERROR_NETWORK = 499;
    public static final int ERROR_CANCELLED_BY_USER = 400;
    public static final int ERROR_DESTINATION = 401;

    public interface Listener {
        void onProgress(long receivedBytes, long totalBytes);
        void onFinished();
        void onFailed(int errorCode, @NonNull String description);
    }

    // Zero once the download has ended.
    private long mNativePtr;
    private final String uri;
    private final String suggestedFilename;
    private final String mimeType;
    private final long contentLength;
    private @Nullable Listener listener;
    // How the download ended, once it did: finished, or failed with this error.
    private boolean ended;
    private int errorCode;
    private @Nullable String errorDescription;

    WebKitDownload(long nativePtr, @NonNull String uri, @NonNull String suggestedFilename, @NonNull String mimeType,
                   long contentLength) {
        mNativePtr = nativePtr;
        this.uri = uri;
        this.suggestedFilename = suggestedFilename;
        this.mimeType = mimeType;
        this.contentLength = contentLength;
        // The native download tells this object how it goes from now on.
        nativeAttach(nativePtr);
    }

    public @NonNull String getUri() { return uri; }

    public @NonNull String getSuggestedFilename() { return suggestedFilename; }

    public @NonNull String getMimeType() { return mimeType; }

    /** The size the server announced, or -1 if it did not. */
    public long getContentLength() { return contentLength; }

    /**
     * Sets the listener, which hears of the end of a download that ended before, as soon as the caller returns: the
     * download may end while the destination is still to be decided.
     */
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
        if (listener != null && ended)
            MainLooperDispatcher.postLater(() -> reportEnd(listener));
    }

    private void reportEnd(@NonNull Listener listener) {
        if (listener != this.listener)
            return;
        if (errorDescription != null)
            listener.onFailed(errorCode, errorDescription);
        else
            listener.onFinished();
    }

    /** Starts writing the download to the file at the path, which is replaced if it exists. */
    public void setDestination(@NonNull String path) {
        if (mNativePtr != 0)
            nativeSetDestination(mNativePtr, path);
    }

    public void cancel() {
        if (mNativePtr != 0)
            nativeCancel(mNativePtr);
    }

    @Keep
    private void onProgress(long receivedBytes, long totalBytes) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onProgress(receivedBytes, totalBytes));
    }

    @Keep
    private void onFinished() {
        mNativePtr = 0;
        ended = true;
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(currentListener::onFinished);
    }

    @Keep
    private void onFailed(int errorCode, @NonNull String description) {
        mNativePtr = 0;
        ended = true;
        this.errorCode = errorCode;
        errorDescription = description;
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onFailed(errorCode, description));
    }

    private native void nativeAttach(long nativePtr);
    private native void nativeSetDestination(long nativePtr, String path);
    private native void nativeCancel(long nativePtr);
}
