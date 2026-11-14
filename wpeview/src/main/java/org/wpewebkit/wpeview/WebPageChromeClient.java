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

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Receives what a {@link WebPage} asks of the app and its user: new windows and closing its own, its title and
 * progress, its JavaScript dialogs, its file and color fields and its sound. Set with {@link WebPage#setChromeClient}.
 * The page may have no view, so each method gets the page, not a {@link WebView}. Each one works as the
 * {@link WebChromeClient} method of the same name, which a view gets for the page it made itself.
 *
 * <p>A JavaScript dialog goes to the app first. If the app does not take it, the view that shows the page shows its
 * built-in dialog, while the page is on screen: while the view has its surface. A page that is not shown, because no
 * view shows it, its view is headless or its activity is stopped, as under another app or a file picker, gets an
 * answer at once, so that it does not wait for a user who cannot see the question, nor make other pages of its web
 * process wait: an {@code alert()} is answered, and the view that shows the page next shows its message;
 * {@code confirm()}, {@code prompt()} and a {@code beforeunload} confirmation are cancelled, with a warning in the
 * console of the page. A cancelled {@code beforeunload} confirmation keeps the page where it is, so that nothing the
 * user typed is lost; the navigation can be started again. A built-in dialog that is open when its page stops being
 * shown, as when the activity stops or the page leaves its view, is answered the same way.
 */
@UiThread
public class WebPageChromeClient {
    /**
     * Asks the app to open a new window for {@code opener}: for a link to a new window, as with target=_blank, or for
     * window.open(). WebKit lets window.open() open one only from a user gesture, such as a tap, and
     * {@link WindowRequest#isUserGesture()} tells whether one asked for this window.
     *
     * <p>To open the window, call {@link WindowRequest#createPage()} before this returns, and give the new page its
     * clients and settings then, such as a user agent of its own: WebKit loads the page of the window into it right
     * after this returns, so load nothing into it, and a client set later misses its first events. The window opens if
     * the page was made, and the new page has {@code opener} as its window.opener, unless it asked for none, as links
     * with target=_blank do. The new page has no view: show it with {@link WebView#setPage}, now or later. When it
     * closes its window, {@link #onCloseWindow} is called for it. Not calling {@code createPage()}, the default, opens
     * no window: window.open() then returns null, and the link does nothing.
     *
     * <p>The app may also make the window with {@link WebView#WebView(android.content.Context, WebView)}, if
     * {@code opener} is in a view, which makes the page of the window for it.
     */
    public void onCreateWindow(@NonNull WebPage opener, @NonNull WindowRequest request) {}

    /**
     * Asks the app to close the window of {@code page}, for window.close() in it, as
     * {@link WebChromeClient#onCloseWindow}. The app destroys the page, which it can do right away.
     */
    public void onCloseWindow(@NonNull WebPage page) {}

    /** Tells the app how far the page has loaded, from 0 to 100. */
    public void onProgressChanged(@NonNull WebPage page, int newProgress) {}

    /** Reports a new title of the page, as {@link WebPage#getTitle()} returns it: its address while it has none. */
    public void onReceivedTitle(@NonNull WebPage page, @NonNull String title) {}

    /** Tells the app that the page started or stopped playing sound, as {@link WebPage#isPlayingAudio()} returns it. */
    public void onPlayingAudioChanged(@NonNull WebPage page, boolean isPlayingAudio) {}

    /**
     * Tells the app of a JavaScript {@code alert()}. Return {@code true} to take over the dialog, and call
     * {@link WPEJsResult#confirm()} when done; return {@code false} for the built-in dialog, or the answer for a page
     * that is not shown.
     */
    public boolean onJsAlert(@NonNull WebPage page, @Nullable String url, @Nullable String message,
                             @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Tells the app of a JavaScript {@code confirm()}. Return {@code true} to take over the dialog, and call
     * {@link WPEJsResult#confirm()} or {@link WPEJsResult#cancel()}; return {@code false} for the built-in dialog, or
     * the answer for a page that is not shown.
     */
    public boolean onJsConfirm(@NonNull WebPage page, @Nullable String url, @Nullable String message,
                               @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Tells the app of a JavaScript {@code prompt()}. Return {@code true} to take over the dialog, and call
     * {@link WPEJsPromptResult#confirm(String)} or {@link WPEJsResult#cancel()}; return {@code false} for the built-in
     * dialog, or the answer for a page that is not shown.
     */
    public boolean onJsPrompt(@NonNull WebPage page, @Nullable String url, @Nullable String message,
                              @NonNull String defaultValue, @NonNull WPEJsPromptResult result) {
        return false;
    }

    /**
     * Tells the app of a JavaScript {@code beforeunload} confirmation, which asks whether to leave the page. Return
     * {@code true} to take over the dialog, and call {@link WPEJsResult#confirm()} to leave or
     * {@link WPEJsResult#cancel()} to stay, for example to let a navigation that the app started on a hidden page go
     * on; return {@code false} for the built-in dialog, or the answer for a page that is not shown, which stays.
     */
    public boolean onJsBeforeUnload(@NonNull WebPage page, @Nullable String url, @Nullable String message,
                                    @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Asks the app to choose files for a file field of the page, as {@link WebChromeClient#onShowFileChooser}. Return
     * {@code false}, the default, to cancel the request, which leaves the field as it was.
     */
    public boolean onShowFileChooser(@NonNull WebPage page, @NonNull FileChooserParams fileChooserParams,
                                     @NonNull WPECallback<String[]> filePathCallback) {
        return false;
    }

    /**
     * Asks the app to choose a color for a color field of the page, as {@link WebChromeClient#onShowColorChooser}.
     * Return {@code false}, the default, to cancel the request, which leaves the field as it was.
     */
    public boolean onShowColorChooser(@NonNull WebPage page, @NonNull ColorChooserParams params,
                                      @NonNull ColorChooserCallback callback) {
        return false;
    }

    /**
     * Tells the app that the page ended the color request it is showing. Its callback does nothing any more. Not called
     * for the request of a page that is destroyed.
     */
    public void onHideColorChooser(@NonNull WebPage page) {}

    /**
     * A request of a page for a new window, given to {@link #onCreateWindow}. It is answered once the call returns:
     * the window opens if {@link #createPage()} made its page.
     */
    public static final class WindowRequest {
        private final WebPage opener;
        private final boolean userGesture;
        // Whether onCreateWindow still runs, and the page made then, if any.
        private boolean open = true;
        private @Nullable WebPage page;
        // Whether the window does not open after all, as when a view's client returned another WebView.
        private boolean declined;

        WindowRequest(@NonNull WebPage opener, boolean userGesture) {
            this.opener = opener;
            this.userGesture = userGesture;
        }

        /** Whether the user asked for the window, with a tap or a key, or whether the page did by itself. */
        public boolean isUserGesture() { return userGesture; }

        /**
         * Makes the page of the new window, which shares the {@link WebContext} of the opener and has settings of its
         * own, starting from the defaults of that context. The app owns it, as any page it makes.
         *
         * @throws IllegalStateException if {@link #onCreateWindow} has returned, or the page was already made, or the
         *     opener is destroyed.
         */
        public @NonNull WebPage createPage() {
            if (!open || page != null || opener.isDestroyed())
                throw new IllegalStateException(
                    "The page of a new window can only be made once, in WebPageChromeClient.onCreateWindow");
            page = new WebPage(opener);
            return page;
        }

        // Whether the page of the window can still be made, for WebView(Context, WebView).
        boolean canCreatePage() { return open && page == null && !opener.isDestroyed(); }

        void decline() { declined = true; }

        @Nullable
        WebPage getPage() {
            return page;
        }

        // Ends the request, and returns the page of the window that opens, if any.
        @Nullable
        WebPage close() {
            open = false;
            return declined || page == null || page.isDestroyed() ? null : page;
        }
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
