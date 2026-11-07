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

import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * WebChromeClient provides the high-level chrome and UI callbacks for {@link WebView}.
 */
public class WebChromeClient {
    public void onCloseWindow(@NonNull WebView window) {}
    public void onProgressChanged(@NonNull WebView view, int newProgress) {}
    /** Reports a new title of the page, as {@link WebView#getTitle()} returns it: its address while it has none. */
    public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {}

    public interface CustomViewCallback {
        void onCustomViewHidden();
    }

    // FIXME: wire WebKit enter-fullscreen / leave-fullscreen signals to invoke these callbacks.
    public void onShowCustomView(@NonNull View view, @NonNull CustomViewCallback callback) {}
    public void onHideCustomView() {}

    /**
     * Notifies the host of a JavaScript {@code alert()}. Return {@code true} to take over the
     * dialog (calling {@link WPEJsResult#confirm()} when done); return {@code false} to let
     * {@link WebView} show its built-in dialog.
     */
    public boolean onJsAlert(@NonNull WebView view, @Nullable String url, @Nullable String message,
                             @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Notifies the host of a JavaScript {@code confirm()}. Return {@code true} to take over the
     * dialog (calling {@link WPEJsResult#confirm()} or {@link WPEJsResult#cancel()}); return
     * {@code false} for the built-in dialog.
     */
    public boolean onJsConfirm(@NonNull WebView view, @Nullable String url, @Nullable String message,
                               @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Notifies the host of a JavaScript {@code prompt()}. Return {@code true} to take over the
     * dialog (calling {@link WPEJsPromptResult#confirm(String)} or {@link WPEJsResult#cancel()});
     * return {@code false} for the built-in dialog.
     */
    public boolean onJsPrompt(@NonNull WebView view, @Nullable String url, @Nullable String message,
                              @NonNull String defaultValue, @NonNull WPEJsPromptResult result) {
        return false;
    }

    /**
     * Notifies the host of a JavaScript {@code beforeunload} confirmation. Return {@code true} to
     * take over the dialog; return {@code false} for the built-in dialog.
     */
    public boolean onJsBeforeUnload(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                    @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Asks the host to choose files for a file field of the page, as when the user taps one. Return {@code true} to
     * take over, and call {@code filePathCallback} once with the absolute paths of the chosen local files, or with
     * {@code null} if the user chose none; it throws IllegalArgumentException for a path that is null or relative.
     * WebKit reads the files by their paths, also later, when the page uploads them, so a content URI is copied to a
     * local file first, which stays while the page may need it. The page sees the last component of each path as the
     * name of the file. Return {@code false} to cancel the request, which leaves the field as it was.
     */
    public boolean onShowFileChooser(@NonNull WebView webView, @NonNull FileChooserParams fileChooserParams,
                                     @NonNull WPECallback<String[]> filePathCallback) {
        return false;
    }

    /** What a file field of the page asks for, given to {@link #onShowFileChooser}. */
    public static final class FileChooserParams {
        /** One file. */
        public static final int MODE_OPEN = 0;
        /** One file or more, for a field with the multiple attribute. */
        public static final int MODE_OPEN_MULTIPLE = 1;

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({MODE_OPEN, MODE_OPEN_MULTIPLE})
        public @interface Mode {}

        private final @Mode int mode;
        private final @NonNull String[] acceptTypes;

        FileChooserParams(@Mode int mode, @NonNull String[] acceptTypes) {
            this.mode = mode;
            this.acceptTypes = acceptTypes;
        }

        public @Mode int getMode() { return mode; }

        /**
         * The MIME types in the accept attribute of the field, such as {@code image/*} or {@code application/pdf},
         * or none if the field takes any file. The file name extensions in that attribute are not given.
         */
        public @NonNull String[] getAcceptTypes() { return acceptTypes.clone(); }
    }

    /**
     * Asks the host to choose a color for a color field of the page, as when the user taps one. Return {@code true} to
     * take over, and end with {@link ColorChooserCallback#confirm} or {@link ColorChooserCallback#cancel}. The page may
     * end it first, with {@link #onHideColorChooser}. Return {@code false} to cancel the request, which leaves the
     * field as it was.
     */
    public boolean onShowColorChooser(@NonNull WebView view, @NonNull ColorChooserParams params,
                                      @NonNull ColorChooserCallback callback) {
        return false;
    }

    /**
     * Tells the host that the page ended the color request it is showing, for example because the field went away or
     * the page was left. Its callback does nothing any more.
     */
    public void onHideColorChooser(@NonNull WebView view) {}

    /** Answers the request of a color field, given to {@link #onShowColorChooser}, once. */
    public interface ColorChooserCallback {
        /** Ends with the color, which the page gets with an input and a change event if it is a new one. */
        void confirm(@ColorInt int color);

        /** Ends with the color the field had, as if it had not asked. */
        void cancel();
    }

    /** What a color field of the page asks for, given to {@link #onShowColorChooser}. */
    public static final class ColorChooserParams {
        private final @ColorInt int color;
        private final boolean supportsAlpha;

        ColorChooserParams(@ColorInt int color, boolean supportsAlpha) {
            this.color = color;
            this.supportsAlpha = supportsAlpha;
        }

        /** The color of the field when it asked. */
        public @ColorInt int getColor() { return color; }

        /** Whether the field takes a color with an alpha, for its alpha attribute. If not, its colors are opaque. */
        public boolean supportsAlpha() { return supportsAlpha; }
    }
}
