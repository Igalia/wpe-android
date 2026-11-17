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
    private final boolean hasGesture;
    // The address that the app asked to download, as it gave it, or null for a download of the page.
    private final @Nullable String requestedUri;
    private @Nullable Listener listener;
    // Hears once that the download no longer waits for a destination: it has one, it was cancelled, or it ended.
    private @Nullable Runnable decidedCallback;
    private boolean cancelled;
    // How the download ended, once it did: finished, or failed with this error.
    private boolean ended;
    private int errorCode;
    private @Nullable String errorDescription;

    WebKitDownload(long nativePtr, @NonNull String uri, @NonNull String suggestedFilename, @NonNull String mimeType,
                   long contentLength, boolean hasGesture, @Nullable String requestedUri) {
        mNativePtr = nativePtr;
        this.uri = uri;
        this.suggestedFilename = suggestedFilename;
        this.mimeType = mimeType;
        this.contentLength = contentLength;
        this.hasGesture = hasGesture;
        this.requestedUri = requestedUri;
        // The native download tells this object how it goes from now on.
        if (nativePtr != 0)
            nativeAttach(nativePtr);
    }

    /**
     * A download that the app asked for, which failed before its response, so without a native download: it has no
     * type or size, and its name is the last part of its address, made a plain name as WebKit makes the names it
     * suggests: without slashes, which the decoded address may hold, control characters, or dots at the start. It is
     * empty when the address has no such part.
     */
    static @NonNull WebKitDownload failedBeforeResponse(@NonNull String uri, int errorCode,
                                                        @NonNull String description) {
        String name = android.net.Uri.parse(uri).getLastPathSegment();
        name = name != null ? name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1) : "";
        name = name.replaceAll("[\\p{Cntrl}]", "_").replaceFirst("^\\.+", "");
        WebKitDownload download = new WebKitDownload(0, uri, name, "", -1, true, uri);
        download.ended = true;
        download.errorCode = errorCode;
        download.errorDescription = description;
        return download;
    }

    public @NonNull String getUri() { return uri; }

    public @NonNull String getSuggestedFilename() { return suggestedFilename; }

    public @NonNull String getMimeType() { return mimeType; }

    /** The size the server announced, or -1 if it did not. */
    public long getContentLength() { return contentLength; }

    /**
     * Whether a user gesture, or a load of the app, started the navigation that became the download, or the app asked
     * for the download.
     */
    public boolean hasGesture() { return hasGesture; }

    /** Whether the app asked for the download, with {@link WebKitWebView#downloadUri}. */
    public boolean isRequestedByApp() { return requestedUri != null; }

    /** The address that the app asked to download, as it gave it, or null for a download of the page. */
    public @Nullable String getRequestedUri() { return requestedUri; }

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

    void setDecidedCallback(@Nullable Runnable callback) { decidedCallback = callback; }

    private void decided() {
        Runnable callback = decidedCallback;
        decidedCallback = null;
        if (callback != null)
            callback.run();
    }

    /**
     * Starts writing the download to the file at the path, which is replaced if it exists. A cancelled download only
     * waits for WebKit to tell its end.
     */
    public void setDestination(@NonNull String path) {
        decided();
        if (mNativePtr != 0 && !cancelled)
            nativeSetDestination(mNativePtr, path);
    }

    public void cancel() {
        decided();
        if (mNativePtr != 0 && !cancelled) {
            cancelled = true;
            nativeCancel(mNativePtr);
        }
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
        decided();
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(currentListener::onFinished);
    }

    @Keep
    private void onFailed(int errorCode, @NonNull String description) {
        mNativePtr = 0;
        ended = true;
        decided();
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
