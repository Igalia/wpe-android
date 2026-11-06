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

package org.wpewebkit.wpeview;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.wpe.WebKitDownload;

import java.io.File;
import java.util.Objects;

/**
 * A response that the WebView saves to a file instead of showing it, as passed to
 * {@link WebViewClient#onDownloadStart}. The WebView fetches it once, with the cookies and the login of the page, and
 * writes it to the file the app gives to {@link #start}, telling the app how it goes. The app may also
 * {@link #cancel} it, and must do one of the two: a download that is neither started nor cancelled waits, until the
 * WebView that started it is destroyed, which cancels it.
 *
 * <p>A started download goes on when the WebView that started it is destroyed, but not when the app's process ends.
 */
@UiThread
public final class WPEDownload {
    /** The connection failed, or the server sent an error. */
    public static final int ERROR_NETWORK = 1;
    /** The file could not be written. */
    public static final int ERROR_DESTINATION = 2;
    /** WebKit cancelled the download, which the app did not ask for. */
    public static final int ERROR_CANCELLED = 3;
    /** Any other failure. */
    public static final int ERROR_UNKNOWN = 4;

    /** Hears how a started download goes, on the main thread. */
    public interface Listener {
        /**
         * More of the download was written. Called often, but not for every piece WebKit receives.
         * @param download The download.
         * @param receivedBytes How much has been written to the file.
         * @param totalBytes The size of the whole download, or -1 if the server did not tell.
         */
        void onProgress(@NonNull WPEDownload download, long receivedBytes, long totalBytes);

        /**
         * The whole download is in the file.
         * @param download The download.
         */
        void onFinished(@NonNull WPEDownload download);

        /**
         * The download failed, and its file was deleted.
         * @param download The download.
         * @param errorCode Why it failed: one of the {@code ERROR_*} constants.
         * @param description WebKit's description of the failure, in English.
         */
        void onFailed(@NonNull WPEDownload download, int errorCode, @NonNull String description);
    }

    private final WebKitDownload download;
    // Package-private: used by the WebKitDownload.Listener inner class (avoids a synthetic accessor).
    @Nullable
    Listener listener;
    private boolean started;
    private boolean cancelled;

    WPEDownload(@NonNull WebKitDownload download) { this.download = download; }

    /** The address of the download, after the redirects of the server. */
    public @NonNull Uri getUrl() { return Uri.parse(download.getUri()); }

    /**
     * The name the server gave the file in its {@code Content-Disposition} header, or else the last part of the
     * address. WebKit makes it a plain name, without slashes or a dot at the start, and replaces some characters, such
     * as spaces, with underscores. It still comes from the server: check it before using it as the name of a file.
     */
    public @NonNull String getSuggestedFileName() { return download.getSuggestedFilename(); }

    /** The MIME type of the download, as the server sent it, without its parameters. Empty if it sent none. */
    public @NonNull String getMimeType() { return download.getMimeType(); }

    /** The size of the download, or -1 if the server did not tell. */
    public long getContentLength() { return download.getContentLength(); }

    /**
     * Starts writing the download to the file, which is replaced if it exists. The WebView's own processes write it,
     * so it must be a path they can write: a file in the app's own storage, such as
     * {@link android.content.Context#getCacheDir()}. While the download goes on, WebKit also writes the file with
     * ".wkdownload" added to its name, and moves it to the file at the end. A download that already failed, while the
     * app decided where to save it, reports its failure to the listener once this method returns.
     *
     * @param destination The file to write.
     * @param listener Hears how the download goes.
     * @throws IllegalStateException if the download was already started or cancelled.
     */
    public void start(@NonNull File destination, @NonNull Listener listener) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(listener, "listener");
        if (started || cancelled)
            throw new IllegalStateException("The download was already " + (started ? "started" : "cancelled"));
        started = true;
        this.listener = listener;
        download.setListener(new WebKitDownload.Listener() {
            @Override
            public void onProgress(long receivedBytes, long totalBytes) {
                Listener current = WPEDownload.this.listener;
                if (current != null)
                    current.onProgress(WPEDownload.this, receivedBytes, totalBytes);
            }

            @Override
            public void onFinished() {
                Listener current = WPEDownload.this.listener;
                WPEDownload.this.listener = null;
                if (current != null)
                    current.onFinished(WPEDownload.this);
            }

            @Override
            public void onFailed(int errorCode, @NonNull String description) {
                Listener current = WPEDownload.this.listener;
                WPEDownload.this.listener = null;
                if (current != null)
                    current.onFailed(WPEDownload.this, publicErrorCode(errorCode), description);
            }
        });
        download.setDestination(destination.getAbsolutePath());
    }

    /**
     * Cancels the download, before or after it starts. The listener hears nothing more of it, and the file is deleted.
     * Does nothing once the download has ended.
     */
    public void cancel() {
        if (cancelled)
            return;
        cancelled = true;
        listener = null;
        download.setListener(null);
        download.cancel();
    }

    // Package-private: called from the WebKitDownload.Listener inner class (avoids a synthetic accessor).
    static int publicErrorCode(int errorCode) {
        switch (errorCode) {
        case WebKitDownload.ERROR_NETWORK:
            return ERROR_NETWORK;
        case WebKitDownload.ERROR_DESTINATION:
            return ERROR_DESTINATION;
        case WebKitDownload.ERROR_CANCELLED_BY_USER:
            return ERROR_CANCELLED;
        default:
            return ERROR_UNKNOWN;
        }
    }
}
