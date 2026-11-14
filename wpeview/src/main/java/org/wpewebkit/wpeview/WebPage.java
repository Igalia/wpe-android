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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Looper;
import android.util.LongSparseArray;
import android.view.Display;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.wpe.LocalNetworkAccess;
import org.wpewebkit.wpe.WPEInputMethodContext;
import org.wpewebkit.wpe.WPERuntime;
import org.wpewebkit.wpe.WPEScreen;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitDownload;
import org.wpewebkit.wpe.WebKitSettings;
import org.wpewebkit.wpe.WebKitWebView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A web page: what it shows, its history, its settings and its scripts, apart from any view. A {@link WebView} shows
 * one page at a time, which {@link WebView#setPage} chooses, and a page is in one view at most. A page needs no
 * activity, only its {@link WebContext}, so it can outlive the views that show it, for example in a
 * {@code ViewModel} while Android rebuilds the activity: it keeps its history, its form contents, its scroll position
 * and its running scripts.
 *
 * <p>A page with no view keeps loading and running, hidden, as a tab in the background. It keeps the size of the last
 * view that showed it. A page that no view showed yet has the size of its opener, for the page of a new window, or the
 * size of the page that a WebView showed last on the default display, or the size of that display before any did, so
 * that it lays out and takes snapshots at a real size.
 *
 * <p>The app owns the pages it makes, and destroys each with {@link #destroy()}, which first takes it out of its view.
 * A WebView made with one of its constructors makes a page of its own and destroys it with itself, unless the app takes
 * it with {@link WebView#releasePage()}. Destroying a {@link WebContext} destroys its pages first. Once destroyed, a
 * page reports nothing more, lets go of its clients, and its methods no longer reach WebKit.
 *
 * <p>A page is used on the main thread: the methods of a page that the app made, which reach WebKit, throw
 * IllegalStateException on any other.
 */
@UiThread
public final class WebPage {
    @FunctionalInterface
    public interface JavascriptCallback {
        void onResult(@NonNull String result);
    }

    @FunctionalInterface
    public interface SnapshotCallback {
        void onResult(@Nullable Bitmap bitmap);
    }

    public static final int SNAPSHOT_REGION_VISIBLE = WebKitWebView.SNAPSHOT_REGION_VISIBLE;
    public static final int SNAPSHOT_REGION_FULL_DOCUMENT = WebKitWebView.SNAPSHOT_REGION_FULL_DOCUMENT;

    @IntDef({SNAPSHOT_REGION_VISIBLE, SNAPSHOT_REGION_FULL_DOCUMENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SnapshotRegion {}

    // The size in logical pixels of the page that a WebView showed last on the default display, which a new page takes.
    // A new array each time, so that a WebView made on another thread reads a whole one.
    private static volatile @Nullable int[] lastShownSize;

    private final WebContext context;
    private final WebKitSettings webKitSettings;
    private final WebSettings settings;
    // Package-private: used by the inner classes (avoids synthetic accessors).
    final WebKitWebView webKitWebView;
    @NonNull
    WebPageClient client = new WebPageClient();
    @NonNull
    WebPageChromeClient chromeClient = new WebPageChromeClient();
    // The view that shows the page, if any.
    @Nullable
    WebView view;
    boolean destroyed;
    // The JavaScript dialogs that are not answered yet, which destroy() cancels.
    final ArrayList<ScriptDialog> pendingScriptDialogs = new ArrayList<>();
    // The last alert() that was answered while the page was not shown, for the next view that shows it.
    @Nullable
    ScriptDialog alertToShow;
    // The request of the color field that the page has open, if any. WebKit has one at most.
    @Nullable
    ColorChooserResult colorChooser;
    // The request of a file field that the chrome client took and has not answered, if any.
    @Nullable
    FileChooserResult fileChooser;
    // The requests for HTTP authentication that the client has not answered, by their id.
    final LongSparseArray<HttpAuthRequest> httpAuthRequests = new LongSparseArray<>();
    // The request for a new window that the chrome client is asked about, while it is.
    @Nullable
    WebPageChromeClient.WindowRequest windowRequest;

    private @Nullable String originalUrl;
    String url = "about:blank";
    // The title of the page, empty while it has none.
    String title = "";
    // The last title the page reported, which getTitle returns.
    @Nullable
    String reportedTitle;
    int progress;
    boolean canGoBack;
    boolean canGoForward;
    // Whether a field of the page has the focus of the input method. WebKit tells when it changes, and has no getter.
    boolean inputFieldFocused;
    // Whether the page plays audio, as it last reported.
    boolean playingAudio;
    // Whether the page was muted when it was destroyed.
    private boolean mutedWhenDestroyed;
    private @ColorInt int backgroundColor = Color.WHITE;
    // Whether a WebView made the page with one of its constructors, and has not handed it to the app.
    boolean madeByView;
    // The size of the page in logical pixels.
    private int width;
    private int height;

    /**
     * Makes a page with its own settings, starting from the defaults of {@code context}, and with clients that do
     * nothing. It loads nothing until it is asked to. The context that a WebView made for itself, which
     * {@link #getWebContext()} gives for its page, ends with the last page made with it: once that is destroyed, no
     * page can be made with it.
     *
     * @throws IllegalStateException if the context is destroyed.
     */
    public WebPage(@NonNull WebContext context) { this(onMainThread(context), null, false); }

    // The page of a new window that opener asks for, made by its WindowRequest.
    WebPage(@NonNull WebPage opener) { this(opener.context, opener, false); }

    // The page of a new window is related to the page of its opener, which asks for it, and shares its context. A page
    // that stays in a toplevel of its own for its whole life, as the one of a headless WebView, keeps the size of that.
    // The constructors of WebView use this one, which does not check the thread, as they never did.
    WebPage(@NonNull WebContext context, @Nullable WebPage opener, boolean inOwnToplevel) {
        if (opener != null)
            context = opener.context;
        if (context.isDestroyed())
            throw new IllegalStateException("The WebContext of the page is destroyed");
        this.context = context;
        webKitSettings = new WebKitSettings();
        webKitSettings.setUserAgentString(context.getDefaultUserAgent());
        webKitWebView = opener != null ? new WebKitWebView(opener.webKitWebView, webKitSettings)
                                       : new WebKitWebView(context.getWPEDisplay(), context.getWebKitWebContext(), null,
                                                           context.getWebKitNetworkSession(), webKitSettings);
        settings = new WebSettings(webKitSettings);
        webKitWebView.setListener(new PageListener());
        webKitWebView.getInputMethodContext().setListener(new InputMethodListener());
        // WebKit gives every new view a toplevel of its own, with the scale of the screen, which the page keeps. The
        // page starts out of any window, and joins the toplevel of the WebView that shows it.
        WPEView wpeView = webKitWebView.getWPEView();
        wpeView.setToplevel(null);
        if (!inOwnToplevel) {
            int[] size = sizeForNewPage(context.getApplicationContext(), opener);
            resize(size[0], size[1]);
        }
        context.addPage(this);
    }

    // A page that no view showed yet lays out at a real size rather than at 0 by 0: the size of its opener, or of the
    // page that a WebView showed last on the default display, or of that display before any did.
    private static int[] sizeForNewPage(@NonNull Context appContext, @Nullable WebPage opener) {
        if (opener != null && opener.width > 0 && opener.height > 0)
            return new int[] {opener.width, opener.height};
        int[] lastShown = lastShownSize;
        if (lastShown != null)
            return lastShown.clone();
        DisplayManager displayManager = appContext.getSystemService(DisplayManager.class);
        Display display = displayManager != null ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
        WPEScreen screen = display != null ? WPERuntime.getInstance().getDisplay().getScreen(display) : null;
        if (screen == null)
            return new int[] {0, 0};
        return new int[] {Math.max(0, screen.getWidth()), Math.max(0, screen.getHeight())};
    }

    // Called by a WebView with the size it gives the page it shows, on the display it shows on.
    static void setLastShownSize(int displayId, int width, int height) {
        if (displayId == Display.DEFAULT_DISPLAY && width > 0 && height > 0)
            lastShownSize = new int[] {width, height};
    }

    // Gives the page a size in logical pixels, which it keeps until it gets another. A page is never 0 by 0.
    void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0)
            return;
        width = newWidth;
        height = newHeight;
        webKitWebView.getWPEView().resized(newWidth, newHeight);
    }

    // The methods that reach WebKit run on the main thread, where its main loop runs. They check it for a page that the
    // app made. The page that a WebView made is not checked, so that apps that use WebView alone see no change.
    void checkThread() {
        if (!madeByView)
            checkMainThread();
    }

    static void checkMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("A WebPage must be used on the main thread");
    }

    private static @NonNull WebContext onMainThread(@NonNull WebContext context) {
        checkMainThread();
        return context;
    }

    /**
     * Sets the client that receives the navigation, load and network events of the page, which replaces the one it had.
     */
    public void setClient(@NonNull WebPageClient client) {
        checkThread();
        this.client = Objects.requireNonNull(client, "client");
    }

    public @NonNull WebPageClient getClient() { return client; }

    /** Sets the client that receives what the page asks of the app and its user, which replaces the one it had. */
    public void setChromeClient(@NonNull WebPageChromeClient client) {
        checkThread();
        this.chromeClient = Objects.requireNonNull(client, "client");
    }

    public @NonNull WebPageChromeClient getChromeClient() { return chromeClient; }

    /** Returns the view that shows the page, or null if it is in none. */
    public @Nullable WebView getView() { return view; }

    /**
     * Returns the context the page was made with, which its cookies, cache and network state belong to. One that a
     * WebView made for itself ends with the last page made with it, so an app that keeps it to make pages of its own
     * can make none once that page is destroyed.
     */
    public @NonNull WebContext getWebContext() { return context; }

    @NonNull
    WebKitWebView getWebKitWebView() {
        return webKitWebView;
    }

    // The proxies are borrowed from the WebKitWebView. They do nothing once the page is destroyed.
    @NonNull
    WPEView getWPEView() {
        return webKitWebView.getWPEView();
    }

    @NonNull
    WPEInputMethodContext getInputMethodContext() {
        return webKitWebView.getInputMethodContext();
    }

    /** Whether the page is destroyed, by {@link #destroy()}, by its view, or by {@link WebContext#destroy()}. */
    public boolean isDestroyed() { return destroyed; }

    /**
     * Returns the settings of the page. Every page has its own, starting from the defaults of its {@link WebContext}.
     * Once the page is destroyed they keep their last values, and changing them has no effect.
     */
    public @NonNull WebSettings getSettings() { return settings; }

    /**
     * Sets the opaque color behind the page, which it shows before its first frame and where it has no background of
     * its own. The default is white. A view that shows the page paints its window with it too. This does not turn on
     * a dark mode or change the colors of the text.
     *
     * @throws IllegalArgumentException if the color is not opaque.
     */
    public void setBackgroundColor(@ColorInt int color) {
        checkThread();
        if (Color.alpha(color) != 255)
            throw new IllegalArgumentException("The background color of a page must be opaque");
        backgroundColor = color;
        webKitWebView.setBackgroundColor(color);
        if (view != null)
            view.onPageBackgroundColorChanged(color);
    }

    public @ColorInt int getBackgroundColor() { return backgroundColor; }

    /** Returns the address of the page, which is "about:blank" before it loads one. */
    public @NonNull String getUrl() { return url; }

    /** Returns the address that the app last asked the page to load, before any redirect, or null if none. */
    public @Nullable String getOriginalUrl() { return originalUrl; }

    /**
     * Returns the title of the page, or while it has none, its address without "http://", "https://", the user name
     * and password, "www." or the "/" after a bare host name, and with spaces unescaped. This is the title that
     * {@link WebPageChromeClient#onReceivedTitle} reports, and it is empty before the first one.
     */
    public @NonNull String getTitle() { return reportedTitle != null ? reportedTitle : ""; }

    /** Returns how far the page has loaded, from 0 to 100. */
    public int getProgress() { return progress; }

    public boolean canGoBack() { return canGoBack; }

    public boolean canGoForward() { return canGoForward; }

    /**
     * Whether the page plays sound, as {@link WebView#isPlayingAudio()} tells it.
     * {@link WebPageChromeClient#onPlayingAudioChanged} tells when it changes. It is false once the page is destroyed.
     */
    public boolean isPlayingAudio() { return playingAudio; }

    /**
     * Mutes or unmutes the sound of the page, as {@link WebView#setAudioMuted} does. The page stays muted for what it
     * loads later, in any view, until it is unmuted. A new page starts unmuted.
     */
    public void setAudioMuted(boolean muted) {
        checkThread();
        webKitWebView.setMuted(muted);
    }

    /** Whether the page is muted. Once it is destroyed, whether it was then. */
    public boolean isAudioMuted() {
        if (destroyed)
            return mutedWhenDestroyed;
        checkThread();
        return webKitWebView.isMuted();
    }

    /**
     * Shows the address of a page without a title as android.webkit.WebView does: without "http://", "https://",
     * the user name and password, or "www.", without the "/" after a bare host name, and with spaces unescaped.
     */
    static @NonNull String titleFromUrl(@NonNull String url) {
        String title;
        if (url.startsWith("http://"))
            title = url.substring("http://".length());
        else if (url.startsWith("https://"))
            title = url.substring("https://".length());
        else
            return url;
        title = title.replaceFirst("^[^/?#]*@", "");
        if (title.startsWith("www."))
            title = title.substring("www.".length());
        if (title.endsWith("/") && title.indexOf('/') == title.length() - 1)
            title = title.substring(0, title.length() - 1);
        return title.replace("%20", " ");
    }

    // A page without a title has its address as its title, so the title changes with the title, and with the address
    // once a navigation is done, not when a load starts, as with android.webkit.WebView.
    void updateTitle() {
        String newTitle = title.isEmpty() ? titleFromUrl(url) : title;
        if (newTitle.equals(reportedTitle))
            return;
        reportedTitle = newTitle;
        chromeClient.onReceivedTitle(this, newTitle);
    }

    public void loadUrl(@NonNull String url) {
        checkThread();
        originalUrl = url;
        webKitWebView.loadUrl(url);
    }

    /** Loads HTML content, with {@code baseUri} as its address, or about:blank if it is null. */
    public void loadHtml(@NonNull String content, @Nullable String baseUri) {
        checkThread();
        originalUrl = baseUri;
        webKitWebView.loadHtml(content, baseUri);
    }

    /** Loads HTML content, as {@link #loadHtml} does: the page always reads the content as UTF-8 HTML. */
    public void loadData(@NonNull String data, @Nullable String mimeType, @Nullable String encoding) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null);
    }

    /**
     * Loads HTML content with {@code baseUrl} as its address, as {@link #loadHtml} does. As with
     * android.webkit.WebView, the other arguments are advisory: the page always reads the content as UTF-8 HTML.
     */
    public void loadDataWithBaseURL(@Nullable String baseUrl, @NonNull String data, @Nullable String mimeType,
                                    @Nullable String encoding, @Nullable String historyUrl) {
        loadHtml(data, baseUrl);
    }

    public void goBack() {
        checkThread();
        webKitWebView.goBack();
    }

    public void goForward() {
        checkThread();
        webKitWebView.goForward();
    }

    public void reload() {
        checkThread();
        webKitWebView.reload();
    }

    public void stopLoading() {
        checkThread();
        webKitWebView.stopLoading();
    }

    /**
     * Ends the web process that runs the page, as {@link WebView#terminateWebProcess()} does. The page then gets
     * {@link WebPageClient#onWebProcessTerminated} with {@link WebPageClient#WEB_PROCESS_TERMINATED_BY_API}, and stays
     * usable.
     */
    public void terminateWebProcess() {
        checkThread();
        webKitWebView.terminateWebProcess();
    }

    /**
     * Saves the history of the page, for {@link #restoreState} to give it to a new page later, as
     * {@link WebView#saveState()} does. Returns null only once the page is destroyed.
     */
    public @Nullable byte[] saveState() {
        if (destroyed)
            return null;
        checkThread();
        return webKitWebView.saveState();
    }

    /**
     * Gives a new page, before it loads anything, the history that {@link #saveState} saved, and loads its current
     * page again, as {@link WebView#restoreState} does. Returns false, and loads nothing, if the state has no page or
     * this version of WPE WebKit cannot read it.
     */
    public boolean restoreState(@NonNull byte[] state) {
        checkThread();
        Objects.requireNonNull(state, "state");
        if (!webKitWebView.restoreState(state))
            return false;
        canGoBack = webKitWebView.canGoBack();
        canGoForward = webKitWebView.canGoForward();
        return true;
    }

    /**
     * Runs a script in the page and gives its result to {@code callback}, if any, on the main thread, as a JSON value.
     * Does nothing once the page is destroyed: the script does not run, and the callback is not called.
     */
    public void evaluateJavascript(@NonNull String script, @Nullable JavascriptCallback callback) {
        checkThread();
        if (!destroyed)
            webKitWebView.evaluateJavascript(script, callback != null ? callback::onResult : null);
    }

    /**
     * Captures a snapshot of the visible part of the page, as {@link WebView#captureSnapshot(WebView.SnapshotCallback)}
     * does, also while the page has no view: the page is painted again at its size. The callback always runs later, on
     * the main thread, with null if the snapshot could not be taken, as when the page is already destroyed.
     */
    public void captureSnapshot(@NonNull SnapshotCallback callback) {
        captureSnapshot(SNAPSHOT_REGION_VISIBLE, callback);
    }

    /**
     * Captures a snapshot of the page for the given {@code region}, as
     * {@link WebView#captureSnapshot(int, WebView.SnapshotCallback)} does.
     *
     * @throws IllegalArgumentException if the region is not {@link #SNAPSHOT_REGION_VISIBLE} or
     *     {@link #SNAPSHOT_REGION_FULL_DOCUMENT}.
     * @throws NullPointerException if the callback is null.
     */
    public void captureSnapshot(@SnapshotRegion int region, @NonNull SnapshotCallback callback) {
        checkThread();
        checkSnapshotArguments(region, 0, 0, callback);
        webKitWebView.captureSnapshot(region, 0, 0, callback::onResult);
    }

    /**
     * Captures a snapshot scaled down to fit in {@code maxWidth} by {@code maxHeight} pixels, as
     * {@link WebView#captureSnapshot(int, int, int, WebView.SnapshotCallback)} does. A limit of 0 leaves that side
     * unlimited.
     *
     * @throws IllegalArgumentException if the region is not {@link #SNAPSHOT_REGION_VISIBLE} or
     *     {@link #SNAPSHOT_REGION_FULL_DOCUMENT}, or {@code maxWidth} or {@code maxHeight} is negative.
     * @throws NullPointerException if the callback is null.
     */
    public void captureSnapshot(@SnapshotRegion int region, int maxWidth, int maxHeight,
                                @NonNull SnapshotCallback callback) {
        checkThread();
        checkSnapshotArguments(region, maxWidth, maxHeight, callback);
        webKitWebView.captureSnapshot(region, maxWidth, maxHeight, callback::onResult);
    }

    static void checkSnapshotArguments(int region, int maxWidth, int maxHeight, @NonNull SnapshotCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (region != SNAPSHOT_REGION_VISIBLE && region != SNAPSHOT_REGION_FULL_DOCUMENT)
            throw new IllegalArgumentException("Unknown snapshot region: " + region);
        if (maxWidth < 0 || maxHeight < 0)
            throw new IllegalArgumentException("Snapshot size limits must not be negative: " + maxWidth + "x" +
                                               maxHeight);
    }

    /** Cancels the JavaScript dialogs that are not answered yet. */
    void cancelScriptDialogs() {
        while (!pendingScriptDialogs.isEmpty())
            pendingScriptDialogs.get(0).cancel();
    }

    // Cancels the requests that wait for an answer from the clients, when they stop hearing of the page: those of the
    // client, for HTTP authentication, and those of the chrome client, JavaScript dialogs and file and color fields.
    // The clients hear that the requests of HTTP authentication and of a color field are gone, as when the page ends
    // them, so that they close what they show for them. Downloads are the app's once they started, and go on. The
    // clients and the requests are taken first: as a client hears of one, it may answer the others, set other clients,
    // or destroy the page, which drops its requests without a word, and the clients still hear of those.
    void cancelRequestsOfClients(boolean ofClient, boolean ofChromeClient) {
        WebPageClient requestsClient = client;
        WebPageChromeClient requestsChromeClient = chromeClient;
        ArrayList<HttpAuthRequest> requests = new ArrayList<>();
        if (ofClient) {
            for (int i = 0; i < httpAuthRequests.size(); i++)
                requests.add(httpAuthRequests.valueAt(i));
        }
        if (ofChromeClient) {
            cancelScriptDialogs();
            if (fileChooser != null)
                fileChooser.onResult(null);
            if (colorChooser != null) {
                colorChooser.cancel();
                requestsChromeClient.onHideColorChooser(this);
            }
        }
        for (HttpAuthRequest request : requests) {
            if (request.ended)
                continue;
            request.cancel();
            requestsClient.onHttpAuthRequestCanceled(this, request);
        }
    }

    /**
     * Destroys the page, after taking it out of the view that shows it. Its JavaScript dialogs and other requests that
     * wait for an answer are cancelled, and its clients do not hear of them. The clients of a WebView that made the
     * page, and goes on, do hear of those for HTTP authentication and color fields, as when the view lets go of the
     * page otherwise. Does nothing if the page is already destroyed.
     */
    public void destroy() {
        if (destroyed)
            return;
        checkThread();
        WebView oldView = view;
        if (oldView != null) {
            oldView.onPageDestroyed(this);
            // The view's clients, which heard there that the requests of the page are gone, may have destroyed it.
            if (destroyed) {
                oldView.reportExitedFullscreen();
                return;
            }
        }
        // Answer the dialogs while the page can still take the answers.
        cancelScriptDialogs();
        alertToShow = null;
        fileChooser = null;
        mutedWhenDestroyed = webKitWebView.isMuted();
        destroyed = true;
        // This also cancels the requests of file and color fields.
        webKitWebView.destroy();
        httpAuthRequests.clear();
        playingAudio = false;
        inputFieldFocused = false;
        settings.detach();
        webKitSettings.destroy();
        // A request that the app still holds, such as an HTTP authentication waiting for credentials, keeps the page,
        // but no longer its clients, nor what they hold, such as a view or an activity.
        client = new WebPageClient();
        chromeClient = new WebPageChromeClient();
        context.removePage(this);
        if (context.endsWithItsPages && !context.hasPages())
            context.destroy();
        // The chrome client of the view hears that the page left fullscreen once the page is gone.
        if (oldView != null)
            oldView.reportExitedFullscreen();
    }

    // WebKit is answered on the main thread, where its main loop runs. Returns whether the task was sent there.
    boolean runOnMainThread(@NonNull Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper())
            return false;
        context.getApplicationContext().getMainExecutor().execute(task);
        return true;
    }

    // The network process tells when Android blocked a connection before it ends it, so it is known by the time the
    // load fails because of it. Ending the connection fails the load with one of these codes; other failures, such as
    // a host name that does not resolve, have another reason. The check needs the application context only, so it
    // works for a page with no view.
    @NonNull
    WPEResourceError loadError(@NonNull Uri uri, int errorCode, @NonNull String description) {
        String host = uri.getHost();
        boolean endedConnection = errorCode == WPEResourceError.ERROR_IO ||
                                  errorCode == WPEResourceError.ERROR_CONNECT ||
                                  errorCode == WPEResourceError.ERROR_UNKNOWN;
        if (!endedConnection || host == null ||
            !LocalNetworkAccess.getInstance().isBlocked(context.getApplicationContext(), host))
            return new WPEResourceError(errorCode, description);
        // WebKit's description would say that the server ended the connection.
        return new WPEResourceError(WPEResourceError.ERROR_LOCAL_NETWORK_ACCESS_DENIED,
                                    "Could not connect to " + host +
                                        ": the app does not have the local network permission");
    }

    // The app goes first. If it does not take the dialog, the view that shows the page shows its own, or the page gets
    // an answer at once if it is not shown. Package-private: called from the PageListener inner class (avoids a
    // synthetic accessor).
    void runScriptDialog(@NonNull ScriptDialog dialog) {
        boolean handled = false;
        if (dialog.type == WebKitWebView.SCRIPT_DIALOG_ALERT)
            handled = chromeClient.onJsAlert(this, dialog.url, dialog.message, dialog);
        else if (dialog.type == WebKitWebView.SCRIPT_DIALOG_CONFIRM)
            handled = chromeClient.onJsConfirm(this, dialog.url, dialog.message, dialog);
        else if (dialog.type == WebKitWebView.SCRIPT_DIALOG_PROMPT)
            handled = chromeClient.onJsPrompt(this, dialog.url, dialog.message, dialog.defaultText, dialog);
        else if (dialog.type == WebKitWebView.SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM)
            handled = chromeClient.onJsBeforeUnload(this, dialog.url, dialog.message, dialog);
        // The app may also have answered without taking the dialog over, or destroyed the page.
        if (handled || dialog.isAnswered())
            return;
        if (view != null && view.isShowingPage())
            view.showScriptDialog(dialog);
        else
            answerScriptDialogWhileNotShown(dialog);
    }

    // A page that is not shown would wait for an answer from a user who cannot see the question, and its scripts, and
    // others in its web process, with it. So it gets an answer at once, which keeps what the user may lose: the message
    // of an alert is kept for the next view that shows the page, and the other dialogs are cancelled, which keeps the
    // page where it is for a beforeunload confirmation. The same answers close a built-in dialog that is open when its
    // page stops being shown. Package-private: also called by the view.
    void answerScriptDialogWhileNotShown(@NonNull ScriptDialog dialog) {
        if (dialog.isAnswered())
            return;
        if (dialog.type == WebKitWebView.SCRIPT_DIALOG_ALERT) {
            alertToShow = dialog;
            dialog.confirm();
            return;
        }
        dialog.cancel();
        String what = dialog.type == WebKitWebView.SCRIPT_DIALOG_PROMPT    ? "prompt() was cancelled"
                      : dialog.type == WebKitWebView.SCRIPT_DIALOG_CONFIRM ? "confirm() was cancelled"
                                                                           : "The page stays: its beforeunload "
                                                                                 + "confirmation was cancelled";
        webKitWebView.warnInConsole(what + ", because the page is not shown");
    }

    class PageListener implements WebKitWebView.Listener {
        @Override
        public void onClose() {
            chromeClient.onCloseWindow(WebPage.this);
        }

        @Override
        public void onPageStarted(@NonNull String startedUrl) {
            client.onPageStarted(WebPage.this, startedUrl);
        }

        @Override
        public void onPageFinished(@NonNull String finishedUrl) {
            client.onPageFinished(WebPage.this, finishedUrl);
        }

        @Override
        public void onURLChanged(@NonNull String uri) {
            url = uri;
        }

        @Override
        public void onLoadProgress(double estimatedProgress) {
            progress = (int)Math.round(estimatedProgress * 100);
            chromeClient.onProgressChanged(WebPage.this, progress);
        }

        @Override
        public void onTitleChanged(@NonNull String newTitle) {
            title = newTitle;
            updateTitle();
        }

        @Override
        public void onHistoryChanged(@NonNull String uri, boolean goBack, boolean goForward) {
            canGoBack = goBack;
            canGoForward = goForward;
            client.doUpdateVisitedHistory(WebPage.this, uri, /* isReload */ false);
            updateTitle();
        }

        @Override
        public void onReceivedHttpError(@NonNull String uri, @NonNull String method, @NonNull String mimeType,
                                        int statusCode, boolean isMainFrame) {
            WPEResourceRequest request = new HttpResourceRequest(Uri.parse(uri), method, isMainFrame, false, false);
            WPEResourceResponse response = new WPEResourceResponse(mimeType, statusCode, Collections.emptyMap());
            client.onReceivedHttpError(WebPage.this, request, response);
        }

        @Override
        public void onReceivedError(@NonNull String uri, @NonNull String method, int errorCode,
                                    @NonNull String description) {
            Uri parsedUri = Uri.parse(uri);
            client.onReceivedError(WebPage.this, new HttpResourceRequest(parsedUri, method, true, false, false),
                                   loadError(parsedUri, errorCode, description));
        }

        @Override
        public void onScriptDialog(long dialogPtr, int type, @Nullable String dialogUrl, @Nullable String message,
                                   @NonNull String defaultText) {
            ScriptDialog dialog = new ScriptDialog(dialogPtr, type, dialogUrl, message, defaultText);
            pendingScriptDialogs.add(dialog);
            runScriptDialog(dialog);
        }

        // The focused field went with the page, and so did the fullscreen element: the view that shows the page lets
        // go of both before the client hears that the page is gone.
        @Override
        public void onWebProcessTerminated(int reason) {
            boolean fieldFocused = inputFieldFocused;
            inputFieldFocused = false;
            if (view != null)
                view.onPageWebProcessTerminated(fieldFocused);
            client.onWebProcessTerminated(WebPage.this, reason);
        }

        // WebKit can only put the page of the new window in a view made for it, in this call.
        @Override
        public @Nullable WebKitWebView onCreateWindow(boolean isUserGesture) {
            WebPageChromeClient.WindowRequest request =
                new WebPageChromeClient.WindowRequest(WebPage.this, isUserGesture);
            windowRequest = request;
            WebPage window;
            try {
                chromeClient.onCreateWindow(WebPage.this, request);
            } catch (RuntimeException | Error exception) {
                // A client that failed opens no window, also if it made its page.
                request.decline();
                throw exception;
            } finally {
                windowRequest = null;
                // The window opens if its page was made, and was not destroyed meanwhile.
                window = request.close();
            }
            return window != null ? window.webKitWebView : null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull String uri, @NonNull String method, boolean isMainFrame,
                                                boolean hasGesture, boolean isRedirect) {
            return client.shouldOverrideUrlLoading(
                WebPage.this, new HttpResourceRequest(Uri.parse(uri), method, isMainFrame, hasGesture, isRedirect));
        }

        @Override
        public void onDownloadStarted(@NonNull WebKitDownload download) {
            client.onDownloadStart(WebPage.this, new WPEDownload(download));
        }

        @Override
        public boolean onRunFileChooser(long requestPtr, @NonNull String[] mimeTypes, boolean selectMultiple) {
            return showFileChooser(requestPtr, mimeTypes, selectMultiple);
        }

        @Override
        public boolean onRunColorChooser(long requestPtr, int color, boolean supportsAlpha) {
            return showColorChooser(requestPtr, color, supportsAlpha);
        }

        @Override
        public void onColorChooserFinished(long requestPtr) {
            ColorChooserResult result = colorChooser;
            if (result == null || result.requestPtr != requestPtr)
                return;
            result.end();
            chromeClient.onHideColorChooser(WebPage.this);
        }

        @Override
        public void onHttpAuthRequest(long requestId, @NonNull String host, int port, @NonNull String scheme,
                                      @NonNull String realm, boolean isProxy, boolean isRetry) {
            HttpAuthRequest handler = new HttpAuthRequest(requestId, scheme, port, isProxy, isRetry);
            httpAuthRequests.put(requestId, handler);
            client.onReceivedHttpAuthRequest(WebPage.this, handler, host, realm);
        }

        @Override
        public void onHttpAuthRequestCancelled(long requestId) {
            HttpAuthRequest handler = httpAuthRequests.get(requestId);
            if (handler == null)
                return;
            handler.ended = true;
            httpAuthRequests.remove(requestId);
            client.onHttpAuthRequestCanceled(WebPage.this, handler);
        }

        @Override
        public void onPlayingAudioChanged(boolean isPlayingAudio) {
            // A destroyed page plays nothing.
            if (destroyed || playingAudio == isPlayingAudio)
                return;
            playingAudio = isPlayingAudio;
            chromeClient.onPlayingAudioChanged(WebPage.this, isPlayingAudio);
        }
    }

    // The page keeps whether a field has the focus, and passes the changes on to the view that shows it, which
    // connects the input method. A change posted before the page was destroyed may arrive after it.
    class InputMethodListener implements WPEInputMethodContext.Listener {
        @Override
        public void onFocusIn() {
            if (destroyed)
                return;
            inputFieldFocused = true;
            if (view != null)
                view.onImeFieldFocused(true);
        }

        @Override
        public void onFocusOut() {
            if (destroyed)
                return;
            inputFieldFocused = false;
            if (view != null)
                view.onImeFieldFocused(false);
        }

        @Override
        public void onPreeditReset() {
            if (!destroyed && view != null)
                view.onImePreeditReset();
        }

        @Override
        public void onContentTypeChanged() {
            if (!destroyed && view != null)
                view.onImeContentTypeChanged();
        }
    }

    /**
     * A JavaScript dialog of the page. WebKit takes one answer per dialog, and the native dialog is released with
     * it, so later answers do nothing.
     */
    final class ScriptDialog implements WPEJsPromptResult {
        private final long dialogPtr;
        final int type;
        final @Nullable String url;
        final @Nullable String message;
        final @NonNull String defaultText;
        private boolean answered;
        private @Nullable Runnable onAnswered;

        ScriptDialog(long dialogPtr, int type, @Nullable String url, @Nullable String message,
                     @NonNull String defaultText) {
            this.dialogPtr = dialogPtr;
            this.type = type;
            this.url = url;
            this.message = message;
            this.defaultText = defaultText;
        }

        boolean isAnswered() { return answered; }

        /** Runs once the dialog is answered, for example to close the built-in dialog that asks the user. */
        void setOnAnswered(@Nullable Runnable onAnswered) { this.onAnswered = onAnswered; }

        @Override
        public void cancel() {
            answer(false, null);
        }

        @Override
        public void confirm() {
            answer(true, null);
        }

        @Override
        public void confirm(@NonNull String result) {
            answer(true, result);
        }

        private void answer(boolean confirmed, @Nullable String text) {
            if (answered)
                return;
            answered = true;
            pendingScriptDialogs.remove(this);
            webKitWebView.scriptDialogConfirm(dialogPtr, confirmed, text);
            webKitWebView.scriptDialogClose(dialogPtr);
            Runnable callback = onAnswered;
            onAnswered = null;
            if (callback != null)
                callback.run();
        }
    }

    // Package-private: called from the PageListener inner class (avoids a synthetic accessor).
    boolean showFileChooser(long requestPtr, @NonNull String[] mimeTypes, boolean selectMultiple) {
        WebPageChromeClient.FileChooserParams params = new WebPageChromeClient.FileChooserParams(
            selectMultiple ? WebPageChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                           : WebPageChromeClient.FileChooserParams.MODE_OPEN,
            mimeTypes);
        FileChooserResult result = new FileChooserResult(requestPtr);
        fileChooser = result;
        boolean taken = false;
        try {
            taken = chromeClient.onShowFileChooser(this, params, result);
        } finally {
            // A request that is not taken is cancelled, so the callback, if the app keeps it, has nothing to answer.
            if (!taken)
                result.end();
        }
        return taken;
    }

    // Package-private, like the field that keeps the request: called by cancelRequestsOfClients.
    class FileChooserResult implements WPECallback<String[]> {
        private final long requestPtr;
        // Once answered, the request is gone, and its pointer may be used by another one.
        boolean answered;

        FileChooserResult(long requestPtr) { this.requestPtr = requestPtr; }

        void end() {
            answered = true;
            if (fileChooser == this)
                fileChooser = null;
        }

        @Override
        public void onResult(@Nullable String[] paths) {
            // WebKit would take a relative path from the directory of its own process. The request is cancelled, so
            // that the field does not wait for another answer.
            if (paths != null) {
                for (String path : paths) {
                    if (path == null || !path.startsWith("/")) {
                        onResult(null);
                        throw new IllegalArgumentException("Not the absolute path of a file: " + path);
                    }
                }
            }
            if (runOnMainThread(() -> onResult(paths)))
                return;
            // Destroying the page cancelled the request.
            if (answered || destroyed)
                return;
            end();
            if (paths == null || paths.length == 0)
                webKitWebView.fileChooserCancel(requestPtr);
            else
                webKitWebView.fileChooserSelect(requestPtr, paths);
        }
    }

    // Package-private: called from the PageListener inner class (avoids a synthetic accessor).
    boolean showColorChooser(long requestPtr, @ColorInt int color, boolean supportsAlpha) {
        ColorChooserResult result = new ColorChooserResult(requestPtr, supportsAlpha);
        colorChooser = result;
        boolean taken = false;
        try {
            taken = chromeClient.onShowColorChooser(
                this, new WebPageChromeClient.ColorChooserParams(color, supportsAlpha), result);
        } finally {
            if (!taken)
                result.end();
        }
        return taken;
    }

    // Package-private, like the field that keeps the request: read by the PageListener inner class. It is also the
    // callback of the view's chrome client.
    class ColorChooserResult implements WebChromeClient.ColorChooserCallback {
        final long requestPtr;
        private final boolean supportsAlpha;
        // Once ended, by the app or by the page, the request is gone, and its pointer may be used by another one.
        private boolean ended;

        ColorChooserResult(long requestPtr, boolean supportsAlpha) {
            this.requestPtr = requestPtr;
            this.supportsAlpha = supportsAlpha;
        }

        void end() {
            ended = true;
            if (colorChooser == this)
                colorChooser = null;
        }

        // WebKit gives the page a change event for each color it is given, not only for the last one, so the field
        // gets only the chosen one.
        @Override
        public void confirm(@ColorInt int color) {
            if (runOnMainThread(() -> confirm(color)) || ended || destroyed)
                return;
            end();
            // A field without an alpha takes only opaque colors.
            webKitWebView.colorChooserSetColor(requestPtr, supportsAlpha ? color : color | 0xff000000);
            webKitWebView.colorChooserFinish(requestPtr);
        }

        @Override
        public void cancel() {
            if (runOnMainThread(this::cancel) || ended || destroyed)
                return;
            end();
            webKitWebView.colorChooserCancel(requestPtr);
        }
    }

    // Package-private, like the field that keeps the requests: read by the PageListener inner class. It is also the
    // handler of the view's client.
    class HttpAuthRequest implements WebViewClient.HttpAuthHandler {
        private final long requestId;
        private final String scheme;
        private final int port;
        private final boolean isProxy;
        private final boolean isRetry;
        // Whether the request ended, answered by the app or cancelled by WebKit. Destroying the page drops it without
        // ending it this way, as nobody hears of that.
        boolean ended;

        HttpAuthRequest(long requestId, @NonNull String scheme, int port, boolean isProxy, boolean isRetry) {
            this.requestId = requestId;
            this.scheme = scheme;
            this.port = port;
            this.isProxy = isProxy;
            this.isRetry = isRetry;
        }

        // The app may answer from any thread, as after it looked the credentials up. Destroying the page cancelled
        // the request.
        @Override
        public void proceed(@NonNull String username, @NonNull String password) {
            if (runOnMainThread(() -> proceed(username, password)) || destroyed)
                return;
            ended = true;
            httpAuthRequests.remove(requestId);
            webKitWebView.authenticate(requestId, username, password);
        }

        @Override
        public void cancel() {
            if (runOnMainThread(this::cancel) || destroyed)
                return;
            ended = true;
            httpAuthRequests.remove(requestId);
            webKitWebView.cancelAuthentication(requestId);
        }

        @Override
        public boolean isProxy() {
            return isProxy;
        }

        @Override
        public boolean isRetry() {
            return isRetry;
        }

        @Override
        public @NonNull String getScheme() {
            return scheme;
        }

        @Override
        public int getPort() {
            return port;
        }
    }

    private static class HttpResourceRequest implements WPEResourceRequest {
        private final Uri uri;
        private final String method;
        private final boolean isForMainFrame;
        private final boolean hasGesture;
        private final boolean isRedirect;
        HttpResourceRequest(Uri uri, String method, boolean isForMainFrame, boolean hasGesture, boolean isRedirect) {
            this.uri = uri;
            this.method = method;
            this.isForMainFrame = isForMainFrame;
            this.hasGesture = hasGesture;
            this.isRedirect = isRedirect;
        }
        @Override
        public @NonNull Uri getUrl() {
            return uri;
        }
        @Override
        public @NonNull String getMethod() {
            return method;
        }
        @Override
        public @NonNull Map<String, String> getRequestHeaders() {
            return Collections.emptyMap();
        }
        @Override
        public boolean isForMainFrame() {
            return isForMainFrame;
        }
        @Override
        public boolean hasGesture() {
            return hasGesture;
        }
        @Override
        public boolean isRedirect() {
            return isRedirect;
        }
    }
}
