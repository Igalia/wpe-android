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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.R;
import org.wpewebkit.wpe.WPEEventType;
import org.wpewebkit.wpe.WPEInputHints;
import org.wpewebkit.wpe.WPEInputMethodContext;
import org.wpewebkit.wpe.WPEInputPurpose;
import org.wpewebkit.wpe.WPERuntime;
import org.wpewebkit.wpe.WPEScreen;
import org.wpewebkit.wpe.WPEToplevel;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitWebView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

/**
 * WebView is the public Android widget for the WPE browser engine. It shows a {@link WebPage}, and handles the
 * Surface lifecycle, input and the built-in dialogs for it.
 *
 * <p>Each of its constructors makes a page that the view owns and destroys with itself, and the page methods of the
 * view, such as {@link #loadUrl}, go to that page, whose events reach the view's {@link WebViewClient} and
 * {@link WebChromeClient}. {@link #setPage} shows another page instead, one that the app made and owns, with clients of
 * its own, for example to keep pages in a {@code ViewModel} while Android rebuilds the activity, or to show the tabs
 * of a browser in one view; {@link #createEmpty} makes a view for that, with no page of its own. The page methods of
 * the view then go to the page it shows. While it shows none, those that do something do nothing, and say so in the
 * log, those that return a value return what a new blank page would, and {@link #getSettings()}, which returns an
 * object of a page, throws IllegalStateException. {@link #getCookieManager()} then returns the one of the
 * {@link WebContext} that the view was made with. It throws if there is none, as for a view from {@link #createEmpty},
 * or for one that made its own and handed it to the app with the page it made, with {@link #releasePage()}, or if that
 * context ended, as the one that a view made for itself does with the last page made with it.
 *
 * <p>A view that shows a page of the app keeps it until it shows another, or none, or is destroyed. A view whose
 * activity is destroyed lets its page go, so that the next activity can show it: it destroys itself when it leaves the
 * window of that activity, unless it shows a page of its own, and {@link #setPage} takes a page from such a view
 * elsewhere. Destroy the view with its activity all the same.
 */
@UiThread
public class WebView extends FrameLayout {
    private static final String LOGTAG = "WPEWebView";

    // The context the view was made with, or made for itself.
    private @Nullable WebContext wpeContext;
    private boolean headless;
    // The page that the view shows, if any. Once the view is destroyed, the page it made, also destroyed, so that the
    // page methods of the view keep giving its last values. Package-private: read by inner classes (avoids synthetic
    // accessors).
    @Nullable
    WebPage page;
    // Whether the view made the page it shows, and destroys it.
    private boolean ownsPage;
    // Whether a method that did nothing for lack of a page was logged, since the view last showed one.
    private boolean warnedOfNoPage;
    // Whether the view made its WebContext, which ends with the last page made with it, and which goes with the page
    // that the view made when it hands that to the app.
    private boolean ownsContext;
    // The clients that pass the events of the page the view made to the view's clients, while the view has that page.
    private @Nullable ViewClientAdapter clientAdapter;
    private @Nullable ViewChromeClientAdapter chromeClientAdapter;
    // Package-private: read by the touch handler of the surface inner class (avoids a synthetic accessor).
    WPEToplevel wpeToplevel;
    // The proxies of the page the view shows, which are null while it shows none.
    @Nullable
    WPEView platformView;
    private @Nullable WPEInputMethodContext imContext;
    @Nullable
    InputMethodManager inputMethodManager;
    // The input type and options that the input method was last restarted for.
    private int imeInputType;
    private int imeOptions;
    // Counts the pages that come and go, so that the input connection made for one page ignores the next.
    private int pageGeneration;
    private PageSurfaceView surfaceView;
    private @Nullable Surface attachedSurface;
    private boolean inToplevel;
    // Whether the page left fullscreen as it left the window, which the chrome client hears once the view is done.
    private boolean exitedFullscreen;
    // The built-in JavaScript dialog that the view shows for its page, and the dialog of the page it answers.
    private @Nullable AlertDialog shownDialog;
    private @Nullable WebPage.ScriptDialog shownScriptDialog;
    // The message of an alert() that the page showed while it was not shown, which the view shows now.
    private @Nullable AlertDialog shownAlertMessage;

    WebViewClient viewClient = new WebViewClient();
    WebChromeClient chromeClient = new WebChromeClient();

    public WebView(@NonNull android.content.Context context) { this(context, (AttributeSet)null); }

    public WebView(@NonNull android.content.Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // Validate XML before allocating the owned context or any native view objects.
        int backgroundColor = initialBackgroundColor();
        WebContext ownContext = new WebContext(context);
        ownContext.endsWithItsPages = true;
        ownsContext = true;
        init(ownContext, new WebPage(ownContext, null, false), false, backgroundColor);
    }

    public WebView(@NonNull WebContext context) {
        super(context.getApplicationContext());
        init(context, new WebPage(context, null, false), false, initialBackgroundColor());
    }

    /**
     * Constructs a {@link WebView} that renders with {@code uiContext} while sharing the given
     * {@link WebContext}. Pass an Activity context (not the application context): it is required to
     * show the built-in JavaScript dialogs and the soft keyboard, mirroring
     * {@code android.webkit.WebView}.
     */
    public WebView(@NonNull android.content.Context uiContext, @NonNull WebContext context) {
        super(uiContext);
        init(context, new WebPage(context, null, false), false, initialBackgroundColor());
    }

    /**
     * Constructs the {@link WebView} of a new window that the page in {@code opener} asks for, which
     * {@link WebChromeClient#onCreateWindow} returns. It can only be made there, once in each call, or in
     * {@link WebPageChromeClient#onCreateWindow} for the page that {@code opener} shows, which makes the page of the
     * window this way. It shares the {@link WebContext} of {@code opener}, and one that {@code opener} made for itself
     * ends with the last page made with it. Like any new WebView, it has settings of its own. Pass an Activity context,
     * as for {@link #WebView(android.content.Context, WebContext)}.
     *
     * @throws IllegalStateException if the page of {@code opener} is not asking for a window there, or a WebView or a
     *     page was already made for it.
     */
    public WebView(@NonNull android.content.Context uiContext, @NonNull WebView opener) {
        super(uiContext);
        WebPage openerPage = opener.page;
        WebPageChromeClient.WindowRequest request = openerPage != null ? openerPage.windowRequest : null;
        if (request == null || !request.canCreatePage())
            throw new IllegalStateException(
                "The WebView of a new window can only be made once in WebChromeClient.onCreateWindow");
        WebPage windowPage = request.createPage();
        init(windowPage.getWebContext(), windowPage, false, initialBackgroundColor());
    }

    /**
     * Constructs a {@link WebView} without an on-screen Surface. JS, navigation and listeners
     * still work; the view simply never gets a backing window so the compositor reports it as
     * unmappable. Used by automation/WebDriver headless sessions. Its page stays in it for its whole life:
     * {@link #setPage} and {@link #releasePage()} throw. Destroying that page all the same, with {@link
     * WebPage#destroy()} or {@link WebContext#destroy()}, leaves the view with a destroyed page, and clients set on the
     * page replace those of the view for its events.
     */
    public WebView(@NonNull WebContext context, boolean headless) {
        super(context.getApplicationContext());
        init(context, new WebPage(context, null, headless), headless, initialBackgroundColor());
    }

    // A view with a page that it made, or with none.
    private WebView(@NonNull android.content.Context uiContext, @Nullable WebContext context,
                    @Nullable WebPage ownPage) {
        super(uiContext);
        init(context, ownPage, false, initialBackgroundColor());
    }

    /**
     * Makes a view that shows no page, for pages that the app makes with {@link WebPage#WebPage(WebContext)} and shows
     * with {@link #setPage}. It has no {@link WebContext} of its own: it uses the one of the page it shows. Pass an
     * Activity context, which the built-in JavaScript dialogs and the soft keyboard need. Until it shows a page, it
     * paints its window with its background color, white by default.
     */
    public static @NonNull WebView createEmpty(@NonNull android.content.Context activityContext) {
        WPERuntime.getInstance().initialize(activityContext);
        return new WebView(activityContext, null, null);
    }

    private void init(@Nullable WebContext context, @Nullable WebPage ownPage, boolean headless,
                      @ColorInt int backgroundColor) {
        this.wpeContext = context;
        this.headless = headless;
        this.inputMethodManager = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        this.wpeToplevel = new WPEToplevel(WPERuntime.getInstance().getDisplay(), null);
        this.wpeToplevel.setListener(this::onFullscreenRequested);
        this.wpeToplevel.setBackgroundColor(backgroundColor);
        // A new SurfaceView cuts its hole in the window only after its first draw. Until then the window shows this
        // view's own background where the page goes, so give it the page color too. This also makes getBackground()
        // match the page color.
        if (getBackground() == null)
            super.setBackgroundColor(backgroundColor);

        if (ownPage != null) {
            ownPage.madeByView = true;
            ownPage.setBackgroundColor(backgroundColor);
            clientAdapter = new ViewClientAdapter(this);
            chromeClientAdapter = new ViewChromeClientAdapter(this);
            ownPage.setClient(clientAdapter);
            ownPage.setChromeClient(chromeClientAdapter);
            ownsPage = true;
            attachPage(ownPage);
            // A headless WebView never gets a surface, so its page stays in its toplevel for its whole life.
            if (headless && platformView != null)
                platformView.setToplevel(wpeToplevel);
        }

        if (!headless) {
            this.surfaceView = new PageSurfaceView(getContext());
            this.surfaceView.getHolder().addCallback(new SurfaceCallback());
            addView(surfaceView);
        }

        setFocusable(true);
        setFocusableInTouchMode(true);
        // The page draws its own focus rings. Android's highlight, drawn when the view gets the focus from a
        // key, would cover the whole page, since it is drawn over the SurfaceView.
        setDefaultFocusHighlightEnabled(false);
    }

    /**
     * Shows {@code newPage} in the view instead of the page it shows now, or no page if it is null. The page that
     * leaves stays as it is, hidden and out of any window, and keeps its last frame for when it is shown again; it
     * shows on screen until the new page draws, for a moment at most. A page the view made itself, with one of its
     * constructors, is destroyed when it leaves: {@link #releasePage()} is the only way to keep it. The view's clients
     * then hear that its requests for HTTP authentication and color fields are gone, as with {@link #releasePage()}.
     *
     * <p>The new page joins the view's window when the view has its surface, or when it gets it. It takes the size of
     * the view, its focus, its visibility and the screen it is on, and the view paints its window with the background
     * color of the page. Its own clients get its events, not the view's: those get only the events of the page the
     * view made. {@link #getSettings()} then gives the settings of the new page.
     *
     * <p>A page is in one view at most. A page in another view is taken from it only if the activity of that view is
     * destroyed, as when the app did not destroy the view with its activity: that view is destroyed then.
     *
     * @throws IllegalStateException if the page is destroyed or is in another view, or if this view is destroyed or
     *     headless.
     */
    public void setPage(@Nullable WebPage newPage) {
        if (headless)
            throw new IllegalStateException("A headless WebView shows the page it made for its whole life");
        if (wpeToplevel == null)
            throw new IllegalStateException("The WebView is destroyed");
        if (newPage == page)
            return;
        if (newPage != null) {
            if (newPage.isDestroyed())
                throw new IllegalStateException("The page is destroyed");
            WebView otherView = newPage.view;
            if (otherView != null) {
                if (otherView.ownsPage || !otherView.isActivityDestroyed())
                    throw new IllegalStateException("The page is in another WebView: take it out of that one first");
                otherView.destroy();
            }
        }
        boolean destroyOldPage = ownsPage;
        WebPage oldPage = detachPage();
        ownsPage = false;
        // The new page joins in the same task as the old one leaves, so that the window shows the old page until the
        // new one draws, rather than the background.
        if (newPage != null)
            attachPage(newPage);
        if (oldPage != null && destroyOldPage) {
            letGoOfOwnPage(oldPage, false);
            oldPage.destroy();
        }
        reportExitedFullscreen();
    }

    /**
     * Returns the page that the view shows, or null if it shows none. Once the view, or the page it made, is destroyed,
     * it returns that page, destroyed, whose methods give its last values. The page that the view made stays the
     * view's: {@link #releasePage()} is the only way to keep it after the view shows another page.
     */
    public @Nullable WebPage getPage() { return page; }

    /**
     * Takes the page out of the view, as {@code setPage(null)} does, and returns it, or null if the view shows none. A
     * page that the view made and that was destroyed on its own is returned destroyed, as {@link #getPage()} gives it.
     * It is the only way to keep the page that the view made, which becomes the app's to destroy, with the
     * {@link WebContext} that the view made for itself, if any: the context goes with the page, and ends with the last
     * page that shares it.
     *
     * <p>The page that the view made then stops reporting to the view's clients. Its clients that passed its events to
     * them become clients that do nothing, and its requests that wait for an answer from them are cancelled: JavaScript
     * dialogs, file and color fields, and HTTP authentication. The view's clients hear of the last two, with
     * {@link WebChromeClient#onHideColorChooser} and {@link WebViewClient#onHttpAuthRequestCanceled}, as when the page
     * ends them itself, so that they close what they show for them. Clients that the app set on the page itself stay,
     * with their requests. Shown again in this view, the page is a page of the app, and the view's clients do not hear
     * of it.
     *
     * @throws IllegalStateException if the view is destroyed or headless.
     */
    public @Nullable WebPage releasePage() {
        if (headless)
            throw new IllegalStateException("A headless WebView shows the page it made for its whole life");
        if (wpeToplevel == null)
            throw new IllegalStateException("The WebView is destroyed");
        boolean releasingOwnPage = ownsPage;
        WebPage released = detachPage();
        ownsPage = false;
        if (released != null && releasingOwnPage) {
            letGoOfOwnPage(released, true);
            if (ownsContext) {
                ownsContext = false;
                wpeContext = null;
            }
        }
        reportExitedFullscreen();
        return released;
    }

    // The view lets go of the page it made: its adapters stop passing events on, also if the app kept them, and they
    // no longer hold the view. Unless the view itself is destroyed, the requests that the page gave to the view's
    // clients are cancelled first, and the clients hear of those for HTTP authentication and color fields, so that
    // they close what they show for them. A page that the view hands over gets clients that do nothing for the adapters
    // it still has. The view is in order while they hear of them: it shows the next page after setPage(), none after
    // releasePage(), and after WebPage.destroy() it keeps the page it made, out of the view, which is destroyed next.
    private void letGoOfOwnPage(@NonNull WebPage ownPage, boolean handOver) {
        ViewClientAdapter oldClientAdapter = clientAdapter;
        ViewChromeClientAdapter oldChromeClientAdapter = chromeClientAdapter;
        // The view no longer has the adapters while its clients hear of the requests, so that what they do then finds
        // the page let go of.
        clientAdapter = null;
        chromeClientAdapter = null;
        if (!ownPage.isDestroyed()) {
            if (handOver)
                ownPage.madeByView = false;
            if (wpeToplevel != null) {
                ownPage.cancelRequestsOfClients(oldClientAdapter != null && ownPage.client == oldClientAdapter,
                                                oldChromeClientAdapter != null &&
                                                    ownPage.chromeClient == oldChromeClientAdapter);
            }
            // The clients may have set others on the page, or destroyed it, which replaced them.
            if (handOver && oldClientAdapter != null && ownPage.client == oldClientAdapter)
                ownPage.setClient(new WebPageClient());
            if (handOver && oldChromeClientAdapter != null && ownPage.chromeClient == oldChromeClientAdapter)
                ownPage.setChromeClient(new WebPageChromeClient());
        }
        if (oldClientAdapter != null)
            oldClientAdapter.view = null;
        if (oldChromeClientAdapter != null)
            oldChromeClientAdapter.view = null;
    }

    private void attachPage(@NonNull WebPage newPage) {
        page = newPage;
        warnedOfNoPage = false;
        newPage.view = this;
        pageGeneration++;
        platformView = newPage.getWPEView();
        imContext = newPage.getInputMethodContext();
        imeInputType = 0;
        imeOptions = 0;
        onPageBackgroundColorChanged(newPage.getBackgroundColor());
        // A page that is not in the window yet still takes the size of the view, to lay out once.
        int[] size = toplevelSize();
        newPage.resize(size[0], size[1]);
        if (attachedSurface != null)
            showPage();
    }

    // Takes the page out of the view, and returns it. The order matters: the page is hidden and loses the focus while
    // it still tells the view about its input method, as leaving takes the focus of its field, then it leaves the
    // window, and its built-in dialog closes. Last, the input method lets go of the field of the page.
    @Nullable
    private WebPage detachPage() {
        WebPage oldPage = page;
        if (oldPage == null)
            return null;
        // The destroyed page that the view made, which it keeps for its last values, left it already.
        if (oldPage.view != this) {
            page = null;
            return oldPage;
        }
        boolean inputMethodConnected = oldPage.inputFieldFocused;
        if (inToplevel) {
            leaveToplevel();
        } else if (platformView != null) {
            platformView.setFocused(false);
            if (headless)
                platformView.setToplevel(null);
        }
        closeDialogsOfPage();
        oldPage.view = null;
        page = null;
        platformView = null;
        imContext = null;
        pageGeneration++;
        if (inputMethodConnected)
            releaseInputMethod();
        return oldPage;
    }

    // The page leaves the view before it is destroyed. The view keeps the page it made, as WebView.destroy() does, so
    // that its methods give the last values of the page, as they did before pages could leave their views, and its
    // clients hear that the requests of the page are gone. Package-private: called by the page when it is destroyed
    // while the view shows it.
    void onPageDestroyed(@NonNull WebPage destroyedPage) {
        if (destroyedPage != page)
            return;
        boolean ownPage = ownsPage;
        detachPage();
        if (ownPage) {
            page = destroyedPage;
            letGoOfOwnPage(destroyedPage, false);
        }
    }

    /**
     * Sets the opaque color behind the page, including before its first frame is available: the background color of
     * the page the view shows, with {@link WebPage#setBackgroundColor}, which the view paints its window with. Showing
     * another page paints the window with the color of that page. The default is white. The same color is used by
     * WebKit for pages without an explicit background; a page's CSS background takes precedence. This does not enable
     * dark mode or change the page's text colors. Call again when the embedding app changes its theme. A solid XML
     * background color is used as the initial color; other Android background drawables do not configure the native
     * page background. To change the color later, call this method: setBackground() and setBackgroundResource() do not
     * change it.
     *
     * <p>Unlike android.webkit.WebView, transparent and translucent backgrounds are not yet
     * supported: the underlying SurfaceView has an opaque background layer.
     *
     * @throws IllegalArgumentException if the color is not opaque
     */
    @Override
    public void setBackgroundColor(@ColorInt int color) {
        if (Color.alpha(color) != 255)
            throw new IllegalArgumentException("WebView background color must be opaque");
        // View construction has no page yet. After destroy() the page does nothing, and the toplevel is gone.
        if (page != null && !page.isDestroyed())
            page.setBackgroundColor(color);
        else if (wpeToplevel != null)
            wpeToplevel.setBackgroundColor(color);
        super.setBackgroundColor(color);
    }

    private int initialBackgroundColor() {
        int color =
            getBackground() instanceof ColorDrawable ? ((ColorDrawable)getBackground()).getColor() : Color.WHITE;
        if (Color.alpha(color) != 255)
            throw new IllegalArgumentException("WebView background color must be opaque");
        return color;
    }

    // The window shows the background color of the page where the page has not drawn. Package-private: called by the
    // page when its color changes.
    void onPageBackgroundColorChanged(@ColorInt int color) {
        if (wpeToplevel != null)
            wpeToplevel.setBackgroundColor(color);
        Drawable background = getBackground();
        if (background == null || background instanceof ColorDrawable)
            super.setBackgroundColor(color);
    }

    // Null while the view shows no page, and once it is destroyed. An automation client that returns a view with no
    // page opens no browsing context.
    @Nullable
    WebKitWebView getInternalWebKitWebView() {
        return page != null && !page.isDestroyed() ? page.getWebKitWebView() : null;
    }

    /**
     * Destroys the view. A page that the view made itself is destroyed with it, and the page methods of the view then
     * keep giving its last values. A page that the app gave it with {@link #setPage} leaves the view, as with
     * {@code setPage(null)}, and stays the app's.
     */
    public void destroy() {
        if (wpeToplevel == null)
            return;
        exitFullscreen();
        // Also a page that left fullscreen as it left the view, when the view's clients destroy the view as they hear
        // that the requests of that page are gone, before the view could tell.
        reportExitedFullscreen();
        // The chrome client may have destroyed the view.
        if (wpeToplevel == null)
            return;
        boolean destroyPage = ownsPage;
        WebPage oldPage = detachPage();
        ownsPage = false;
        wpeToplevel.destroy();
        wpeToplevel = null;
        if (oldPage != null && destroyPage) {
            letGoOfOwnPage(oldPage, false);
            oldPage.destroy();
            page = oldPage;
        }
    }

    // Whether the activity of the view is destroyed, so that the view can no longer be shown.
    private boolean isActivityDestroyed() {
        Activity activity = activityOf(getContext());
        return activity != null && activity.isDestroyed();
    }

    // The size of the view in logical pixels, which its page takes.
    private int[] toplevelSize() {
        int[] size = new int[2];
        if (wpeToplevel != null)
            wpeToplevel.getSize(size);
        return size;
    }

    void attachToplevelSurface(@NonNull Surface surface) {
        if (wpeToplevel == null)
            return;
        if (attachedSurface == surface)
            return;
        attachedSurface = surface;
        updateScreen();
        joinToplevel();
        wpeToplevel.onSurfaceCreated(surface);
        if (platformView != null)
            showPage();
    }

    void detachToplevelSurface() {
        if (wpeToplevel != null) {
            leaveToplevel();
            closeDialogsOfPage();
            attachedSurface = null;
            wpeToplevel.onSurfaceDestroyed();
            reportExitedFullscreen();
        }
    }

    // The page joins the window, at the size of the view, and the message of an alert it kept for a view comes up.
    private void showPage() {
        joinToplevel();
        int[] size = toplevelSize();
        if (page != null && platformView != null && size[0] > 0 && size[1] > 0) {
            page.resize(size[0], size[1]);
            platformView.setMapped(true);
            WebPage.setLastShownSize(displayId(), size[0], size[1]);
        }
        showDialogsOfPage();
    }

    // The id of the display the view shows on, or of the default display before it is in a window.
    private int displayId() {
        Display display = getDisplay();
        return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    // The page gets the screen of the display the WebView shows on, which changes when its window moves to another
    // display.
    private void updateScreen() {
        Display display = getDisplay();
        if (wpeToplevel == null || display == null)
            return;
        WPEScreen screen = WPERuntime.getInstance().getDisplay().getScreen(display);
        if (screen != null)
            wpeToplevel.setScreen(screen);
    }

    // The page is in the toplevel, and so in a window, while the WebView shows it and has a surface. Android takes the
    // surface away when the WebView leaves its window and when its activity stops.
    private void joinToplevel() {
        if (platformView == null || inToplevel || headless || attachedSurface == null)
            return;
        inToplevel = true;
        platformView.setToplevel(wpeToplevel);
        wpeToplevel.setWindowFocused(hasWindowFocus());
        platformView.setFocused(isFocused());
        platformView.setVisible(isShown());
    }

    // Leaving the toplevel neither unmaps the view nor clears the focus and activity it took from it, so this
    // does both before the view leaves. A page out of its window cannot stay fullscreen, and WebKit only sees the
    // toplevel leave fullscreen while the view is in it. The chrome client hears of it once the view is done with the
    // page, through reportExitedFullscreen(), so that what it does then finds the view and the page in order.
    private void leaveToplevel() {
        if (platformView == null || !inToplevel)
            return;
        inToplevel = false;
        platformView.setMapped(false);
        platformView.setFocused(false);
        wpeToplevel.setWindowFocused(false);
        if (wpeToplevel.isFullscreen()) {
            wpeToplevel.setFullscreen(false);
            exitedFullscreen = true;
        }
        platformView.setToplevel(null);
    }

    // Tells the chrome client that the page left fullscreen as it left the window. Package-private: called by the page
    // once it is destroyed.
    void reportExitedFullscreen() {
        if (!exitedFullscreen)
            return;
        exitedFullscreen = false;
        chromeClient.onExitFullscreen(this);
    }

    // Whether the page is on screen, so that the view can show its built-in dialogs. Package-private: called by the
    // page.
    boolean isShowingPage() { return inToplevel; }

    // WebKit asks the toplevel to enter fullscreen when an element of the page goes fullscreen, and to leave it when
    // the page leaves fullscreen by itself. The toplevel has done it already.
    private void onFullscreenRequested(boolean fullscreen) {
        if (fullscreen)
            chromeClient.onEnterFullscreen(this);
        else
            chromeClient.onExitFullscreen(this);
    }

    /**
     * Whether an element of the page is fullscreen, from {@link WebChromeClient#onEnterFullscreen} until
     * {@link WebChromeClient#onExitFullscreen}.
     */
    public boolean isFullscreen() { return wpeToplevel != null && wpeToplevel.isFullscreen(); }

    /**
     * Takes the page out of fullscreen, as the user asks with Back, and calls {@link WebChromeClient#onExitFullscreen}
     * right away. The page sees it a moment later, with a {@code fullscreenchange} event. Does nothing if the page is
     * not fullscreen.
     */
    public void exitFullscreen() {
        if (!isFullscreen())
            return;
        // WebKit takes the page out of fullscreen when the toplevel leaves it, without asking the toplevel again.
        wpeToplevel.setFullscreen(false);
        chromeClient.onExitFullscreen(this);
    }

    /**
     * Sets the client of the view. It gets the events of the page that the view made, and {@link #onViewReady} for
     * any page. A page that the app gives the view has its own {@link WebPageClient}.
     */
    public void setWebViewClient(@NonNull WebViewClient client) { this.viewClient = client; }
    public @NonNull WebViewClient getWebViewClient() { return viewClient; }

    /**
     * Sets the chrome client of the view. It gets fullscreen for any page, and the other events of the page that the
     * view made. A page that the app gives the view has its own {@link WebPageChromeClient}.
     */
    public void setWebChromeClient(@NonNull WebChromeClient client) { this.chromeClient = client; }
    public @NonNull WebChromeClient getWebChromeClient() { return chromeClient; }

    /** The address of the page, as {@link WebPage#getUrl()}, or "about:blank" while the view shows no page. */
    public @NonNull String getUrl() { return page != null ? page.getUrl() : "about:blank"; }
    public @Nullable String getOriginalUrl() { return page != null ? page.getOriginalUrl() : null; }
    /**
     * Returns the title of the page, or while it has none, its address without "http://", "https://", the user name
     * and password, "www." or the "/" after a bare host name, and with spaces unescaped. This is the title that
     * {@link WebChromeClient#onReceivedTitle} reports, and it is empty before the first one, as with
     * android.webkit.WebView, and while the view shows no page.
     */
    public @NonNull String getTitle() { return page != null ? page.getTitle() : ""; }

    public int getProgress() { return page != null ? page.getProgress() : 0; }
    public boolean canGoBack() { return page != null && page.canGoBack(); }
    public boolean canGoForward() { return page != null && page.canGoForward(); }

    /**
     * Whether the page plays sound, from audio and video elements or from Web Audio, also while it is muted
     * ({@link #setAudioMuted}), so that the app can show which page plays and whether it is heard. Media that the page
     * muted counts too, as WebKit counts it on this platform, but media at volume 0 does not. Neither does media that
     * is paused, nor media that pauses while the page is hidden, with
     * {@link WebSettings#setAudiblePlaybackRequiresPageVisibility}. It is false once the web process of the page ends,
     * after {@link #destroy()}, and while the view shows no page. {@link WebChromeClient#onPlayingAudioChanged} tells
     * when it changes.
     */
    public boolean isPlayingAudio() { return page != null && page.isPlayingAudio(); }

    /**
     * Mutes or unmutes the sound of the page, for example for a tab that the user mutes. The page plays on, and its
     * media elements keep their own muted and volume values. The page stays muted for what it loads later, also after
     * its web process ends, until it is unmuted, and showing another page does not change it: the setting belongs to
     * the page ({@link WebPage#setAudioMuted}). Every page starts unmuted, also one that a muted page opens for a new
     * window. After {@link #destroy()}, and while the view shows no page, this does nothing.
     */
    public void setAudioMuted(boolean muted) {
        WebPage target = pageFor("setAudioMuted");
        if (target != null)
            target.setAudioMuted(muted);
    }

    /**
     * Whether the page is muted ({@link #setAudioMuted}). After {@link #destroy()}, whether it was then. False while
     * the view shows no page.
     */
    public boolean isAudioMuted() { return page != null && page.isAudioMuted(); }

    public void loadUrl(@NonNull String url) {
        WebPage target = pageFor("loadUrl");
        if (target != null)
            target.loadUrl(url);
    }

    public void loadHtml(@NonNull String content, @Nullable String baseUri) {
        WebPage target = pageFor("loadHtml");
        if (target != null)
            target.loadHtml(content, baseUri);
    }

    public void loadData(@NonNull String data, @Nullable String mimeType, @Nullable String encoding) {
        WebPage target = pageFor("loadData");
        if (target != null)
            target.loadData(data, mimeType, encoding);
    }

    public void loadDataWithBaseURL(@Nullable String baseUrl, @NonNull String data, @Nullable String mimeType,
                                    @Nullable String encoding, @Nullable String historyUrl) {
        WebPage target = pageFor("loadDataWithBaseURL");
        if (target != null)
            target.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
    }

    public void goBack() {
        WebPage target = pageFor("goBack");
        if (target != null)
            target.goBack();
    }

    public void goForward() {
        WebPage target = pageFor("goForward");
        if (target != null)
            target.goForward();
    }

    public void reload() {
        WebPage target = pageFor("reload");
        if (target != null)
            target.reload();
    }

    public void stopLoading() {
        WebPage target = pageFor("stopLoading");
        if (target != null)
            target.stopLoading();
    }

    /**
     * Ends the web process that runs the page, for example when the page stops responding. The view then gets
     * {@link WebViewClient#onWebProcessTerminated} with {@link WebViewClient#WEB_PROCESS_TERMINATED_BY_API}, and stays
     * usable: {@link #reload()} loads the page again. Other views whose pages run in the same web process lose them
     * too.
     */
    public void terminateWebProcess() {
        WebPage target = pageFor("terminateWebProcess");
        if (target != null)
            target.terminateWebProcess();
    }

    /**
     * Saves the history of the page, for {@link #restoreState} to give it to a new WebView later, for example after
     * Android ended the app: the Back/Forward list with its current page, and for each page its address, its title, and
     * the scroll position and form contents that WebKit kept for it. Cookies, storage and the state of scripts are not
     * in it. Returns null once the view is destroyed, and while it shows no page.
     * <p>
     * The state grows with the history and the form contents, which may be private, so keep it in the app's own
     * storage, such as a file. In the Bundle of {@link android.app.Activity#onSaveInstanceState} a large state makes
     * Android fail with a {@link android.os.TransactionTooLargeException}. Save the address of the current page with
     * the state, to load the page if {@link #restoreState} cannot read it.
     */
    public @Nullable byte[] saveState() { return page != null ? page.saveState() : null; }

    /**
     * Gives a new WebView, before it loads anything, the history that {@link #saveState} saved, and loads its current
     * page again. {@link #canGoBack} and {@link #canGoForward} follow the restored Back/Forward list at once, while the
     * page loads. Returns false, and loads nothing, if the state has no page, or if this version of WPE WebKit cannot
     * read it, as one saved by a newer version: the app can load the address that it saved with the state instead. Also
     * returns false while the view shows no page.
     */
    public boolean restoreState(@NonNull byte[] state) {
        Objects.requireNonNull(state, "state");
        WebPage target = pageFor("restoreState");
        return target != null && target.restoreState(state);
    }

    /**
     * Returns the settings of the page that the view shows. Every page has its own, starting from the defaults of its
     * {@link WebContext}, so changing them does not affect other pages, and the view gives those of another page once
     * it shows another: settings kept from before go on changing the page they came from. After {@link #destroy()} they
     * keep their last values, and changing them has no effect.
     *
     * @throws IllegalStateException if the view shows no page.
     */
    public @NonNull WebSettings getSettings() {
        if (page == null)
            throw new IllegalStateException("The WebView shows no page, which settings belong to");
        return page.getSettings();
    }

    /**
     * Returns the cookie manager of the {@link WebContext} of the page, or of the view while it shows no page.
     *
     * @throws IllegalStateException if the view shows no page and has no WebContext: it was made without one, or it
     *     handed the one it made for itself to the app with {@link #releasePage()}, or that one ended with the last
     *     page made with it, as after {@code setPage(null)} when no other page shares it.
     */
    public @NonNull CookieManager getCookieManager() {
        WebContext context = currentContext();
        if (context == null)
            throw new IllegalStateException("The WebView shows no page and has no WebContext");
        return context.getCookieManager();
    }

    // The context of the page, or while the view shows none, the context it was made with, unless it is destroyed, as
    // the one a view made for itself is once the last page made with it is.
    @Nullable
    private WebContext currentContext() {
        WebContext context = page != null ? page.getWebContext() : wpeContext;
        return context != null && !context.isDestroyed() ? context : null;
    }

    // The page that a method of the view goes to. While the view shows none, a method that does something does nothing,
    // and says so in the log.
    @Nullable
    private WebPage pageFor(@NonNull String method) {
        if (page == null && !warnedOfNoPage)
            warnOfNoPage(method + "() does nothing: the WebView shows no page");
        return page;
    }

    // Logs that a method did nothing for lack of a page, once until the view shows a page again.
    private void warnOfNoPage(@NonNull String message) {
        if (warnedOfNoPage)
            return;
        warnedOfNoPage = true;
        Log.w(LOGTAG, message + ". Later calls are not logged until it shows one.");
    }

    /** A {@link WebPage.JavascriptCallback}, for {@link #evaluateJavascript}. */
    @FunctionalInterface
    public interface JavascriptCallback extends WebPage.JavascriptCallback {}

    /** A {@link WebPage.SnapshotCallback}, for {@link #captureSnapshot}. */
    @FunctionalInterface
    public interface SnapshotCallback extends WebPage.SnapshotCallback {}

    public static final int SNAPSHOT_REGION_VISIBLE = WebPage.SNAPSHOT_REGION_VISIBLE;
    public static final int SNAPSHOT_REGION_FULL_DOCUMENT = WebPage.SNAPSHOT_REGION_FULL_DOCUMENT;

    // The same values as WebPage.SnapshotRegion, so that a region passes from one to the other.
    @IntDef({WebPage.SNAPSHOT_REGION_VISIBLE, WebPage.SNAPSHOT_REGION_FULL_DOCUMENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SnapshotRegion {}

    /**
     * Captures a snapshot of the currently visible page content and delivers it as a
     * {@link android.graphics.Bitmap} to {@code callback} on the main thread. The callback always runs after
     * this method returns, never synchronously. The caller owns the returned bitmap.
     * <p>
     * The snapshot is a new paint of the page by WebKit, not a copy of the screen: WebGL
     * canvases appear in it, but playing video does not.
     * <p>
     * The bitmap is in device pixels, but WebKit sizes the page in whole CSS pixels, so at a
     * fractional density it can be a pixel or two smaller than the view: a 1080 by 2400 view at
     * density 2.625 gives a 1078 by 2399 snapshot. Use the size of the bitmap, not of the view.
     * <p>
     * The bitmap is null if the snapshot could not be taken, for example because the view was
     * already destroyed when this method was called, or showed no page, or the bitmap could not be allocated. A
     * snapshot that is already running when the view is destroyed still delivers its bitmap.
     */
    public void captureSnapshot(@NonNull SnapshotCallback callback) {
        captureSnapshot(SNAPSHOT_REGION_VISIBLE, callback);
    }

    /**
     * Captures a snapshot of the page content for the given {@code region}, which must be
     * {@link #SNAPSHOT_REGION_VISIBLE} or {@link #SNAPSHOT_REGION_FULL_DOCUMENT}, and delivers it
     * like {@link #captureSnapshot(SnapshotCallback)}.
     * <p>
     * A full document snapshot can be very large. The bitmap is in device pixels, 4 bytes each,
     * and WebKit does not limit its size, so a long page can take hundreds of megabytes. That
     * memory is held twice for a moment, by WebKit and by the bitmap, while the pixels are
     * copied. Android does not draw bitmaps larger than 100 MB on a hardware accelerated canvas.
     * To get a smaller bitmap, use {@link #captureSnapshot(int, int, int, SnapshotCallback)}.
     *
     * @throws IllegalArgumentException if the region is not one of the two above.
     * @throws NullPointerException if the callback is null.
     */
    public void captureSnapshot(@SnapshotRegion int region, @NonNull SnapshotCallback callback) {
        captureSnapshot(region, 0, 0, callback);
    }

    /**
     * Captures a snapshot like {@link #captureSnapshot(int, SnapshotCallback)}, scaled down to fit in
     * {@code maxWidth} by {@code maxHeight} pixels, with the same aspect ratio rounded to whole pixels. A limit
     * of 0 leaves that side unlimited, for example to limit only the width. A snapshot that already fits keeps its
     * size: it is never enlarged.
     * <p>
     * The pixels are scaled while they are copied out of WebKit, so only the scaled bitmap is allocated, which
     * suits thumbnails: at full size, a visible snapshot of a 1080 by 2400 view takes about 10 MB. WebKit still
     * paints the snapshot at full size for a moment, in native memory.
     *
     * @throws IllegalArgumentException if the region is not {@link #SNAPSHOT_REGION_VISIBLE} or
     *     {@link #SNAPSHOT_REGION_FULL_DOCUMENT}, or {@code maxWidth} or {@code maxHeight} is negative.
     * @throws NullPointerException if the callback is null.
     */
    public void captureSnapshot(@SnapshotRegion int region, int maxWidth, int maxHeight,
                                @NonNull SnapshotCallback callback) {
        WebPage.checkSnapshotArguments(region, maxWidth, maxHeight, callback);
        WebPage target = pageFor("captureSnapshot");
        if (target != null)
            target.captureSnapshot(region, maxWidth, maxHeight, callback);
        else
            getContext().getMainExecutor().execute(() -> callback.onResult(null));
    }

    /**
     * Runs a script in the page and gives its result to {@code callback}, if any, as {@link WebPage#evaluateJavascript}
     * does. Does nothing while the view shows no page, or once its page is destroyed: the script does not run, and the
     * callback is not called.
     */
    public void evaluateJavascript(@NonNull String script, @Nullable JavascriptCallback callback) {
        WebPage target = pageFor("evaluateJavascript");
        if (target != null)
            target.evaluateJavascript(script, callback);
    }

    /** Sets the policy for TLS errors of the {@link WebContext} of the page, or of the view while it shows no page. */
    public void setTLSErrorsPolicy(int policy) {
        WebContext context = currentContext();
        if (context != null)
            context.getWebKitNetworkSession().setTLSErrorsPolicy(policy);
        else
            warnOfNoPage("setTLSErrorsPolicy() does nothing: the WebView shows no page and has no WebContext");
    }

    // The page that a view made reports to the view's clients, through these adapters, while the view has it. Once the
    // view lets go of the page, they no longer hold the view, and do what the default clients do, as they do once the
    // view is destroyed. They are static, so that they hold the view only through that field.
    static final class ViewClientAdapter extends WebPageClient {
        @Nullable
        WebView view;

        ViewClientAdapter(@NonNull WebView view) { this.view = view; }

        // The view, until the adapter lets go of it or the view is destroyed.
        @Nullable
        WebView target() {
            WebView target = view;
            return target != null && target.wpeToplevel != null ? target : null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull WebPage source, @NonNull WPEResourceRequest request) {
            WebView target = target();
            return target != null && target.viewClient.shouldOverrideUrlLoading(target, request);
        }

        @Override
        public void onDownloadStart(@NonNull WebPage source, @NonNull WPEDownload download) {
            WebView target = target();
            if (target != null)
                target.viewClient.onDownloadStart(target, download);
            else
                super.onDownloadStart(source, download);
        }

        @Override
        public void onPageStarted(@NonNull WebPage source, @NonNull String url) {
            WebView target = target();
            if (target != null)
                target.viewClient.onPageStarted(target, url);
        }

        @Override
        public void onPageFinished(@NonNull WebPage source, @NonNull String url) {
            WebView target = target();
            if (target != null)
                target.viewClient.onPageFinished(target, url);
        }

        @Override
        public void doUpdateVisitedHistory(@NonNull WebPage source, @NonNull String url, boolean isReload) {
            WebView target = target();
            if (target != null)
                target.viewClient.doUpdateVisitedHistory(target, url, isReload);
        }

        @Override
        public void onReceivedHttpError(@NonNull WebPage source, @NonNull WPEResourceRequest request,
                                        @NonNull WPEResourceResponse errorResponse) {
            WebView target = target();
            if (target != null)
                target.viewClient.onReceivedHttpError(target, request, errorResponse);
        }

        @Override
        public void onReceivedError(@NonNull WebPage source, @NonNull WPEResourceRequest request,
                                    @NonNull WPEResourceError error) {
            WebView target = target();
            if (target != null)
                target.viewClient.onReceivedError(target, request, error);
        }

        @Override
        public void onWebProcessTerminated(@NonNull WebPage source, int reason) {
            WebView target = target();
            if (target != null)
                target.viewClient.onWebProcessTerminated(target, reason);
        }

        // The page makes its handlers for the view's client too.
        @Override
        public void onReceivedHttpAuthRequest(@NonNull WebPage source, @NonNull WebPageClient.HttpAuthHandler handler,
                                              @NonNull String host, @NonNull String realm) {
            WebView target = target();
            if (target != null && handler instanceof WebViewClient.HttpAuthHandler)
                target.viewClient.onReceivedHttpAuthRequest(target, (WebViewClient.HttpAuthHandler)handler, host,
                                                            realm);
            else
                super.onReceivedHttpAuthRequest(source, handler, host, realm);
        }

        @Override
        public void onHttpAuthRequestCanceled(@NonNull WebPage source, @NonNull WebPageClient.HttpAuthHandler handler) {
            WebView target = target();
            if (target != null && handler instanceof WebViewClient.HttpAuthHandler)
                target.viewClient.onHttpAuthRequestCanceled(target, (WebViewClient.HttpAuthHandler)handler);
        }
    }

    // Package-private: see ViewClientAdapter.
    static final class ViewChromeClientAdapter extends WebPageChromeClient {
        @Nullable
        WebView view;

        ViewChromeClientAdapter(@NonNull WebView view) { this.view = view; }

        // The view, until the adapter lets go of it or the view is destroyed.
        @Nullable
        WebView target() {
            WebView target = view;
            return target != null && target.wpeToplevel != null ? target : null;
        }

        // WebKit can only put the page of the new window in a view made for it, in this call: the one that
        // WebView(Context, WebView) makes with the request. A window that the view's client does not return opens not,
        // since WebKit is waiting for the window, rather than throwing into it.
        @Override
        public void onCreateWindow(@NonNull WebPage opener, @NonNull WindowRequest request) {
            WebView target = target();
            if (target == null)
                return;
            WebView window = target.chromeClient.onCreateWindow(target, request.isUserGesture());
            if (window == null) {
                request.decline();
                return;
            }
            WebPage created = request.getPage();
            if (created == null || window.page != created) {
                Log.e(LOGTAG, "WebChromeClient.onCreateWindow must return the WebView it made for the window with "
                                  + "WebView(Context, WebView), or null: no window opens");
                request.decline();
            }
        }

        @Override
        public void onCloseWindow(@NonNull WebPage source) {
            WebView target = target();
            if (target != null)
                target.chromeClient.onCloseWindow(target);
        }

        @Override
        public void onProgressChanged(@NonNull WebPage source, int newProgress) {
            WebView target = target();
            if (target != null)
                target.chromeClient.onProgressChanged(target, newProgress);
        }

        @Override
        public void onReceivedTitle(@NonNull WebPage source, @NonNull String title) {
            WebView target = target();
            if (target != null)
                target.chromeClient.onReceivedTitle(target, title);
        }

        @Override
        public void onPlayingAudioChanged(@NonNull WebPage source, boolean isPlayingAudio) {
            WebView target = target();
            if (target != null)
                target.chromeClient.onPlayingAudioChanged(target, isPlayingAudio);
        }

        @Override
        public boolean onJsAlert(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                 @NonNull WPEJsResult result) {
            WebView target = target();
            return target != null && target.chromeClient.onJsAlert(target, url, message, result);
        }

        @Override
        public boolean onJsConfirm(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                   @NonNull WPEJsResult result) {
            WebView target = target();
            return target != null && target.chromeClient.onJsConfirm(target, url, message, result);
        }

        @Override
        public boolean onJsPrompt(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                  @NonNull String defaultValue, @NonNull WPEJsPromptResult result) {
            WebView target = target();
            return target != null && target.chromeClient.onJsPrompt(target, url, message, defaultValue, result);
        }

        @Override
        public boolean onJsBeforeUnload(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                        @NonNull WPEJsResult result) {
            WebView target = target();
            return target != null && target.chromeClient.onJsBeforeUnload(target, url, message, result);
        }

        // The view's chrome client gets the types it always had, made from those of the page.
        @Override
        public boolean onShowFileChooser(@NonNull WebPage source, @NonNull FileChooserParams fileChooserParams,
                                         @NonNull WPECallback<String[]> filePathCallback) {
            WebView target = target();
            return target != null && target.chromeClient.onShowFileChooser(
                                         target,
                                         new WebChromeClient.FileChooserParams(fileChooserParams.getMode(),
                                                                               fileChooserParams.getAcceptTypes()),
                                         filePathCallback);
        }

        // The page makes its callbacks for the view's chrome client too.
        @Override
        public boolean onShowColorChooser(@NonNull WebPage source, @NonNull ColorChooserParams params,
                                          @NonNull ColorChooserCallback callback) {
            WebView target = target();
            return target != null && callback instanceof WebChromeClient.ColorChooserCallback &&
                target.chromeClient.onShowColorChooser(
                    target, new WebChromeClient.ColorChooserParams(params.getColor(), params.supportsAlpha()),
                    (WebChromeClient.ColorChooserCallback)callback);
        }

        @Override
        public void onHideColorChooser(@NonNull WebPage source) {
            WebView target = target();
            if (target != null)
                target.chromeClient.onHideColorChooser(target);
        }
    }

    // The page is gone, and with it its focused field and its fullscreen element. Package-private: called by the page.
    void onPageWebProcessTerminated(boolean inputFieldFocused) {
        if (inputFieldFocused)
            releaseInputMethod();
        exitFullscreen();
    }

    // The title of a built-in dialog: the origin of the page that asks.
    @Nullable
    private static String dialogTitle(@Nullable String url) {
        if (url == null)
            return null;
        try {
            URL alertUrl = new URL(url);
            return "The page at " + alertUrl.getProtocol() + "://" + alertUrl.getHost() + " says";
        } catch (MalformedURLException ex) {
            // Keep the raw URL as the title.
            return url;
        }
    }

    // Shows the built-in dialog for a dialog of the page that the app did not take. Package-private: called by the
    // page.
    void showScriptDialog(@NonNull WebPage.ScriptDialog dialog) {
        int type = dialog.type;
        String message = dialog.message;
        String title = dialogTitle(dialog.url);
        String displayMessage = message;
        int positiveTextId = R.string.ok;
        int negativeTextId = R.string.cancel;
        if (type == WebKitWebView.SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM) {
            title = getContext().getString(R.string.js_dialog_before_unload_title);
            displayMessage = getContext().getString(R.string.js_dialog_before_unload, message);
            positiveTextId = R.string.js_dialog_before_unload_positive_button;
            negativeTextId = R.string.js_dialog_before_unload_negative_button;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(title);
        builder.setOnCancelListener(new ScriptDialogCancelListener(dialog));
        if (type != WebKitWebView.SCRIPT_DIALOG_PROMPT) {
            builder.setMessage(displayMessage);
            builder.setPositiveButton(positiveTextId, new ScriptDialogPositiveListener(dialog, null));
        } else {
            @SuppressLint("InflateParams")
            final View view = LayoutInflater.from(getContext()).inflate(R.layout.js_prompt, null);
            EditText edit = view.findViewById(R.id.value);
            edit.setText(dialog.defaultText);
            builder.setPositiveButton(positiveTextId, new ScriptDialogPositiveListener(dialog, edit));
            ((TextView)view.findViewById(R.id.message)).setText(message);
            builder.setView(view);
        }
        if (type != WebKitWebView.SCRIPT_DIALOG_ALERT) {
            builder.setNegativeButton(negativeTextId, new ScriptDialogCancelListener(dialog));
        }
        builder.setOnDismissListener(this::onScriptDialogDismissed);
        try {
            AlertDialog dialogShown = builder.show();
            shownDialog = dialogShown;
            shownScriptDialog = dialog;
            dialog.setOnAnswered(() -> {
                if (dialogShown.isShowing())
                    dialogShown.dismiss();
            });
        } catch (WindowManager.BadTokenException exception) {
            // Android shows dialogs only for a running activity, and the WebView's context may be the application.
            Log.w("WPEAndroid", "Cannot show the JavaScript dialog, cancelling it", exception);
            dialog.cancel();
        }
    }

    private void onScriptDialogDismissed(@NonNull DialogInterface dismissed) {
        if (dismissed != shownDialog)
            return;
        shownDialog = null;
        shownScriptDialog = null;
    }

    // Shows the message of an alert() that the page showed while it was not shown. The page was answered then.
    private void showAlertMessage(@NonNull WebPage alertPage, @NonNull WebPage.ScriptDialog alert) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(dialogTitle(alert.url));
        builder.setMessage(alert.message);
        builder.setPositiveButton(R.string.ok, null);
        builder.setOnDismissListener(dismissed -> {
            // A dialog that the view closed when the page stopped being shown is no longer the shown one, and the
            // page keeps the message for the next view.
            if (dismissed != shownAlertMessage)
                return;
            shownAlertMessage = null;
            if (alertPage.alertToShow == alert)
                alertPage.alertToShow = null;
        });
        try {
            shownAlertMessage = builder.show();
        } catch (WindowManager.BadTokenException exception) {
            Log.w(LOGTAG, "Cannot show the message of a JavaScript alert", exception);
            alertPage.alertToShow = null;
        }
    }

    // The page is shown again, so the view shows the message of its last alert() while it was not shown.
    private void showDialogsOfPage() {
        WebPage shownPage = page;
        if (shownPage == null)
            return;
        if (shownPage.alertToShow != null && shownAlertMessage == null)
            showAlertMessage(shownPage, shownPage.alertToShow);
    }

    // The page stops being shown, so its built-in dialogs close with the answers of a page that is not shown: an alert
    // is answered and its message kept for the next view, and the other dialogs are cancelled. The message of an
    // earlier alert that the view shows stays with the page too.
    private void closeDialogsOfPage() {
        AlertDialog alertMessage = shownAlertMessage;
        shownAlertMessage = null;
        if (alertMessage != null)
            alertMessage.dismiss();
        WebPage.ScriptDialog dialog = shownScriptDialog;
        AlertDialog dialogShown = shownDialog;
        shownScriptDialog = null;
        shownDialog = null;
        if (dialog == null || dialogShown == null || page == null)
            return;
        page.answerScriptDialogWhileNotShown(dialog);
        // An answered dialog closes when it is answered.
        if (dialogShown.isShowing())
            dialogShown.dismiss();
    }

    private static class ScriptDialogCancelListener
        implements DialogInterface.OnCancelListener, DialogInterface.OnClickListener {
        private final WPEJsResult result;

        ScriptDialogCancelListener(@NonNull WPEJsResult result) { this.result = result; }

        @Override
        public void onCancel(DialogInterface dialog) {
            result.cancel();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            result.cancel();
        }
    }

    private static class ScriptDialogPositiveListener implements DialogInterface.OnClickListener {
        private final WPEJsPromptResult result;
        private final @Nullable EditText edit;

        ScriptDialogPositiveListener(@NonNull WPEJsPromptResult result, @Nullable EditText edit) {
            this.result = result;
            this.edit = edit;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (edit != null) {
                result.confirm(edit.getText().toString());
            } else {
                result.confirm();
            }
        }
    }

    /**
     * Internal SurfaceView that handles the drawing surface.
     */
    class PageSurfaceView extends SurfaceView {
        // A tap brings back the keyboard of the focused field, which Back may have hidden: one finger that stays
        // within the touch slop and is lifted before a long press. A scroll, a pinch or a long press does not.
        private final GestureDetector tapDetector;

        public PageSurfaceView(android.content.Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(@NonNull MotionEvent event) {
                    if (isInputFieldFocused())
                        showSoftInput();
                    return true;
                }
            });
            // The second tap of a double tap is a tap too.
            tapDetector.setOnDoubleTapListener(null);
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent event) {
            if (platformView == null || wpeToplevel == null)
                return false;
            tapDetector.onTouchEvent(event);

            // MotionEvent coords are in physical pixels; WPE expects logical pixels, at the scale of the screen the
            // page is on.
            float density = wpeToplevel.getScale();

            int action = event.getActionMasked();
            int type;
            int actionIndex;
            switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                WebView.this.requestFocus();
                type = WPEEventType.TOUCH_DOWN;
                actionIndex = event.getActionIndex();
                platformView.dispatchTouchEvent(
                    event.getEventTime(), type, 1, new int[] {event.getPointerId(actionIndex)},
                    new float[] {event.getX(actionIndex) / density}, new float[] {event.getY(actionIndex) / density});
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                type = WPEEventType.TOUCH_UP;
                actionIndex = event.getActionIndex();
                platformView.dispatchTouchEvent(
                    event.getEventTime(), type, 1, new int[] {event.getPointerId(actionIndex)},
                    new float[] {event.getX(actionIndex) / density}, new float[] {event.getY(actionIndex) / density});
                return true;
            case MotionEvent.ACTION_MOVE:
                type = WPEEventType.TOUCH_MOVE;
                break;
            case MotionEvent.ACTION_CANCEL:
                type = WPEEventType.TOUCH_CANCEL;
                break;
            default:
                return false;
            }

            // For MOVE and CANCEL send all active pointers
            int pointerCount = event.getPointerCount();
            int[] ids = new int[pointerCount];
            float[] xs = new float[pointerCount];
            float[] ys = new float[pointerCount];
            for (int i = 0; i < pointerCount; i++) {
                ids[i] = event.getPointerId(i);
                xs[i] = event.getX(i) / density;
                ys[i] = event.getY(i) / density;
            }
            platformView.dispatchTouchEvent(event.getEventTime(), type, pointerCount, ids, xs, ys);
            return true;
        }
    }

    // Whether a field of the page the view shows has the focus of the input method. Package-private: read by the
    // surface inner class (avoids a synthetic accessor).
    boolean isInputFieldFocused() { return page != null && page.inputFieldFocused; }

    // Whether an input connection made while the view showed its page of that generation still goes to it.
    // Package-private: called by WPEInputConnection.
    boolean isInputConnectionCurrent(int generation) { return generation == pageGeneration && platformView != null; }

    int getPageGeneration() { return pageGeneration; }

    // The page tells when its field gains or loses the focus. Package-private: called by the page.
    void onImeFieldFocused(boolean focused) {
        if (inputMethodManager == null || wpeToplevel == null)
            return;
        if (focused) {
            // restartInput refreshes EditorInfo (and re-invokes onCreateInputConnection) for the new field.
            updateImeContentType();
            inputMethodManager.restartInput(this);
            showSoftInput();
        } else if (getWindowToken() != null) {
            inputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // Asks the input method to show its keyboard for the focused field.
    void showSoftInput() {
        if (inputMethodManager != null)
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    // WebKit ended the composition (a tap in the page, for instance) and kept its text. The input method still
    // thinks it is composing and would send the whole word again with its next key, so restart it. Package-private:
    // called by the page.
    void onImePreeditReset() {
        if (!isInputFieldFocused() || inputMethodManager == null)
            return;
        inputMethodManager.restartInput(this);
    }

    // The focused field changed its purpose or hints without losing focus, as a password field does when a "show
    // password" button turns it into a text field, or the focus moved to another field: WebKit tells no focus out or
    // focus in for that. Restart the input method if the field now needs another input type or options.
    // Package-private: called by the page.
    void onImeContentTypeChanged() {
        if (!isInputFieldFocused() || inputMethodManager == null)
            return;
        if (updateImeContentType())
            inputMethodManager.restartInput(this);
    }

    // Takes the input type and options of the focused field, and returns whether they changed.
    private boolean updateImeContentType() {
        if (imContext == null)
            return false;
        int purpose = imContext.getInputPurpose();
        int hints = imContext.getInputHints();
        int inputType = inputTypeForPurpose(purpose, hints);
        int options = imeOptionsForPurpose(purpose, hints);
        if (inputType == imeInputType && options == imeOptions)
            return false;
        imeInputType = inputType;
        imeOptions = options;
        return true;
    }

    // A field that has been a password field has the private hint, also when a "show password" button turns it into
    // a text field. It gets a visible password keyboard, which neither suggests nor learns what is typed.
    private static int inputTypeForPurpose(int purpose, int hints) {
        boolean isPrivate = (hints & WPEInputHints.PRIVATE) != 0;
        switch (purpose) {
        case WPEInputPurpose.DIGITS:
            return EditorInfo.TYPE_CLASS_NUMBER | (isPrivate ? EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD : 0);
        case WPEInputPurpose.NUMBER:
            return EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_FLAG_SIGNED |
                EditorInfo.TYPE_NUMBER_FLAG_DECIMAL;
        case WPEInputPurpose.PHONE:
            return EditorInfo.TYPE_CLASS_PHONE;
        case WPEInputPurpose.URL:
            return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI;
        case WPEInputPurpose.EMAIL:
            return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        case WPEInputPurpose.PASSWORD:
            return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        case WPEInputPurpose.PIN:
            return EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD;
        default:
            if (isPrivate)
                return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT |
                capitalizationFlags(purpose, hints);
        }
    }

    // WebKit gives the lowercase hint for autocapitalize=none, and no hint when the page asks for nothing.
    private static int capitalizationFlags(int purpose, int hints) {
        if ((hints & WPEInputHints.UPPERCASE_CHARS) != 0)
            return EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS;
        if ((hints & WPEInputHints.UPPERCASE_WORDS) != 0)
            return EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS;
        if ((hints & WPEInputHints.UPPERCASE_SENTENCES) != 0)
            return EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if (purpose == WPEInputPurpose.FREE_FORM && (hints & WPEInputHints.LOWERCASE) == 0)
            return EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES;
        return 0;
    }

    // The password variations keep most keyboards from learning what is typed. The flag asks it of every keyboard.
    private static int imeOptionsForPurpose(int purpose, int hints) {
        int action = purpose == WPEInputPurpose.SEARCH ? EditorInfo.IME_ACTION_SEARCH : EditorInfo.IME_ACTION_NONE;
        int options = action | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        if (purpose == WPEInputPurpose.PASSWORD || purpose == WPEInputPurpose.PIN ||
            (hints & WPEInputHints.PRIVATE) != 0)
            options |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return options;
    }

    // Makes an input method connected to a field that is gone let go of it: restarting the input asks for a new
    // connection, which the view no longer gives.
    private void releaseInputMethod() {
        if (inputMethodManager == null)
            return;
        inputMethodManager.restartInput(this);
        if (getWindowToken() != null)
            inputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        if (!isInputFieldFocused() || imContext == null)
            return null;
        int purpose = imContext.getInputPurpose();
        int hints = imContext.getInputHints();
        outAttrs.inputType = inputTypeForPurpose(purpose, hints);
        outAttrs.imeOptions = imeOptionsForPurpose(purpose, hints);
        outAttrs.actionLabel = null;
        return new WPEInputConnection(this, /* fullEditor */ true, imContext);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return isInputFieldFocused();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        // Hiding the WebView or one of its ancestors keeps its surface; WPE unmaps a view that is not visible. A hidden
        // page cannot stay fullscreen either, as when a tab is hidden in place.
        if (platformView != null && !headless) {
            platformView.setVisible(isVisible);
            if (!isVisible)
                exitFullscreen();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (inToplevel)
            wpeToplevel.setWindowFocused(hasWindowFocus);
    }

    @Override
    protected void onDetachedFromWindow() {
        // The SurfaceView child detaches first and normally loses its surface then; a surface still here is let
        // go of the same way, so a later attach with it joins the toplevel again.
        if (attachedSurface != null)
            detachToplevelSurface();
        super.onDetachedFromWindow();
        // A page of the app outlives the activity, which may end without destroying the view. The view destroys itself
        // when it leaves the window of its destroyed activity, which lets the page go, so that the page neither keeps
        // the activity alive nor stays in a view that is gone, and frees its toplevel. A view that made its page keeps
        // it as it always did, until the app destroys the view.
        if (!ownsPage && isActivityDestroyed())
            destroy();
    }

    @Nullable
    private static Activity activityOf(@NonNull Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity)
                return (Activity)context;
            context = ((ContextWrapper)context).getBaseContext();
        }
        return null;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, @Nullable android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        // Mirror the widget's focus onto the WebKit view so DOM focus, document.hasFocus() and the
        // input method engage; without this a focused editable never raises the IME.
        if (platformView != null)
            platformView.setFocused(gainFocus);
    }

    // Keys that Android handles, as in GeckoView: the volume keys change the volume, and Back, Forward, Menu, Search
    // and the headset button go to the app or the system. They are not sent to the page, which would consume them.
    private static boolean isKeyForAndroid(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_VOLUME_UP:
        case KeyEvent.KEYCODE_VOLUME_DOWN:
        case KeyEvent.KEYCODE_VOLUME_MUTE:
        case KeyEvent.KEYCODE_BACK:
        case KeyEvent.KEYCODE_FORWARD:
        case KeyEvent.KEYCODE_MENU:
        case KeyEvent.KEYCODE_SEARCH:
        case KeyEvent.KEYCODE_HEADSETHOOK:
            return true;
        default:
            return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (platformView == null || isKeyForAndroid(event.getKeyCode()))
            return super.dispatchKeyEvent(event);

        int action = event.getAction();
        int type;
        if (action == KeyEvent.ACTION_DOWN)
            type = WPEEventType.KEYBOARD_KEY_DOWN;
        else if (action == KeyEvent.ACTION_UP)
            type = WPEEventType.KEYBOARD_KEY_UP;
        else
            return super.dispatchKeyEvent(event);

        platformView.dispatchKeyEvent(event.getEventTime(), type, event.getKeyCode(), event.getUnicodeChar(),
                                      event.getMetaState());
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (wpeToplevel == null)
            return;
        wpeToplevel.setPhysicalSize(w, h);
        int[] size = toplevelSize();
        if (platformView == null || size[0] <= 0 || size[1] <= 0)
            return;
        page.resize(size[0], size[1]);
        if (inToplevel)
            WebPage.setLastShownSize(displayId(), size[0], size[1]);
    }

    class SurfaceCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            attachToplevelSurface(holder.getSurface());
            viewClient.onViewReady(WebView.this);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            attachToplevelSurface(holder.getSurface());
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            detachToplevelSurface();
        }
    }
}
