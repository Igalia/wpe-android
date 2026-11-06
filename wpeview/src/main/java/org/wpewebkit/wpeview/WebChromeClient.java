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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * WebChromeClient provides the high-level chrome and UI callbacks for {@link WebView}.
 */
public class WebChromeClient {
    /**
     * Asks the app to open a new window for the page in {@code view}: for a link to a new window, as with
     * target=_blank, or for window.open(). WebKit lets window.open() open one only from a user gesture, such as a tap,
     * and {@code isUserGesture} tells whether one asked for this window.
     *
     * <p>To open the window, make its WebView with {@link WebView#WebView(android.content.Context, WebView)}, give it
     * its clients, show it, and return it, all before this returns: WebKit needs it right away. WebKit then loads the
     * page into it, so load nothing into it. The page there has the page in {@code view} as its window.opener, unless
     * it asked for none, as links with target=_blank do. When it closes its window, {@link #onCloseWindow} is called
     * with the new WebView.
     *
     * <p>Return null to open no window, which is the default: window.open() then returns null, and the link does
     * nothing. A WebView made for the window but not returned is the app's to destroy. Returning any other WebView
     * opens no window either, and logs an error. The app may destroy {@code view} here, once it made the window.
     *
     * <p>Unlike android.webkit.WebChromeClient, this does not tell whether the window is a dialog: WebKit knows the
     * features the page asked for only once the window is made.
     */
    public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) { return null; }

    /**
     * Asks the app to close {@code window}, for window.close() in its page, which WebKit allows in a window that a page
     * opened ({@link #onCreateWindow}), or with a single page in its history. The app takes the WebView out of its
     * layout and destroys it, which it can do right away.
     */
    public void onCloseWindow(@NonNull WebView window) {}
    public void onProgressChanged(@NonNull WebView view, int newProgress) {}
    /** Reports a new title of the page, as {@link WebView#getTitle()} returns it: its address while it has none. */
    public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {}

    /**
     * Tells the app that an element of the page went fullscreen, as a video does with its fullscreen button, or any
     * element with {@code element.requestFullscreen()}. The element now fills the {@link WebView}, which draws the page
     * itself, so there is no other view to show: the app gives the WebView the whole screen where it is, for example
     * by hiding the controls around it and the system bars, until {@link #onExitFullscreen}. Fullscreen ends when the
     * WebView is hidden or leaves its window, as when it is moved to another parent or its activity stops.
     *
     * <p>The app takes the page out of fullscreen with {@link WebView#exitFullscreen()}, for example on Back or when
     * it shows another page. An app that does not show pages fullscreen can call it from here. If the app does
     * nothing, the element fills the WebView where it is.
     */
    public void onEnterFullscreen(@NonNull WebView view) {}

    /**
     * Tells the app that the page left fullscreen, by itself, as with {@code document.exitFullscreen()} or the
     * controls of a video, or because of {@link WebView#exitFullscreen()}. The app brings back what it changed in
     * {@link #onEnterFullscreen}.
     */
    public void onExitFullscreen(@NonNull WebView view) {}

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
