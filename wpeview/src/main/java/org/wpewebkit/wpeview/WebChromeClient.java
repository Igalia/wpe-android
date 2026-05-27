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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * WebChromeClient provides the high-level chrome and UI callbacks for {@link WebView}.
 */
public class WebChromeClient {
    public void onCloseWindow(@NonNull WebView window) {}
    public void onProgressChanged(@NonNull WebView view, int newProgress) {}
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
}
