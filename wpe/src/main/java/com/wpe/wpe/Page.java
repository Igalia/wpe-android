package com.wpe.wpe;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.wpe.wpe.gfx.WPESurfaceView;
import com.wpe.wpe.services.WPEServiceConnection;
import com.wpe.wpeview.WPEView;

/**
 * A Page roughly corresponds with a tab in a regular browser UI.
 * There is a 1:1 relationship between WPEView and Page.
 * Each Page instance has its own wpe.wpe.gfx.View and WebKitWebView instances associated.
 * It also keeps references to the Services that host the logic of WebKit's auxiliary
 * processes (WebProcess and NetworkProcess).
 */
@UiThread
public class Page {
    public static final int LOAD_STARTED = 0;
    public static final int LOAD_REDIRECTED = 1;
    public static final int LOAD_COMMITTED = 2;
    public static final int LOAD_FINISHED = 3;

    private final String LOGTAG;
    private final int id;
    private final Browser browser;
    private final Context context;
    private final WPEView wpeView;
    private final int width;
    private final int height;
    private final WPESurfaceView view;
    private final boolean canGoBack = true;
    private final boolean canGoForward = true;

    private boolean closed = false;
    private boolean viewReady = false;
    private boolean pageGlueReady = false;
    private String pendingLoad;

    public Page(@NonNull Browser browser, @NonNull Context context, @NonNull WPEView wpeView, int pageId) {
        LOGTAG = "WPE page" + pageId;

        Log.v(LOGTAG, "Page construction " + this);

        id = pageId;

        this.browser = browser;
        this.context = context;
        this.wpeView = wpeView;

        width = wpeView.getMeasuredWidth();
        height = wpeView.getMeasuredHeight();

        view = new WPESurfaceView(context, pageId, wpeView);
        wpeView.onSurfaceViewCreated(view);
        onViewReady();

        ensurePageGlue();
    }

    public void close() {
        if (closed)
            return;

        closed = true;
        Log.v(LOGTAG, "Page destruction");
        BrowserGlue.closePage(id);
        pageGlueReady = false;
    }

    /**
     * Callback triggered when the Page glue is ready and the associated WebKitWebView
     * instance is created.
     * This is called by the JNI layer. See `Java_com_wpe_wpe_BrowserGlue_newPage`
     */
    @Keep
    public void onPageGlueReady() {
        Log.v(LOGTAG, "WebKitWebView ready " + pageGlueReady);
        pageGlueReady = true;

        updateAllSettings();
        wpeView.getSettings().getPageSettings().setPage(this);

        if (viewReady) {
            loadUrlInternal();
        }
    }

    private void ensurePageGlue() {
        if (pageGlueReady) {
            onPageGlueReady();
            return;
        }

        // Requests the creation of a new WebKitWebView. On creation, the `onPageGlueReady` callback
        // is triggered.
        BrowserGlue.newPage(this, id, width, height);
    }

    public void onViewReady() {
        Log.d(LOGTAG, "onViewReady");
        wpeView.onSurfaceViewReady(view);
        viewReady = true;
        if (pageGlueReady) {
            loadUrlInternal();
        }
    }

    @WorkerThread
    public WPEServiceConnection launchService(@NonNull ProcessType processType, @NonNull ParcelFileDescriptor parcelFd,
                                              @NonNull Class<?> serviceClass) {
        Log.v(LOGTAG, "launchService type: " + processType.name());
        Intent intent = new Intent(context, serviceClass);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, this, parcelFd);
        switch (processType) {
        case WebProcess:
            // FIXME: we probably want to kill the current web process here if any exists when PSON is enabled.
            browser.setWebProcess(serviceConnection);
            break;

        case NetworkProcess:
            browser.setNetworkProcess(serviceConnection);
            break;

        default:
            throw new IllegalArgumentException("Unknown process type");
        }

        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        return serviceConnection;
    }

    @WorkerThread
    public void stopService(@NonNull WPEServiceConnection serviceConnection) {
        Log.v(LOGTAG, "stopService type: " + serviceConnection.getProcessType().name());
        // FIXME: Until we fully support PSON, we won't do anything here.
    }

    public WPESurfaceView view() { return view; }

    private void loadUrlInternal() {
        if (pendingLoad == null) {
            return;
        }
        BrowserGlue.loadURL(id, pendingLoad);
        pendingLoad = null;
    }

    public void loadUrl(@NonNull Context context, @NonNull String url) {
        Log.d(LOGTAG, "Queue URL load " + url);
        pendingLoad = url;
        ensurePageGlue();
    }

    public void onLoadChanged(int loadEvent) {
        wpeView.onLoadChanged(loadEvent);
        if (loadEvent == Page.LOAD_STARTED) {
            dismissKeyboard();
        }
    }

    public void onLoadProgress(double progress) { wpeView.onLoadProgress(progress); }

    public void onUriChanged(String uri) { wpeView.onUriChanged(uri); }

    public void onTitleChanged(String title, boolean canGoBack, boolean canGoForward) {
        canGoBack = canGoBack;
        canGoForward = canGoForward;
        wpeView.onTitleChanged(title);
    }

    public void onInputMethodContextIn() {
        InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    private void dismissKeyboard() {
        InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public void onInputMethodContextOut() { dismissKeyboard(); }

    public void enterFullscreenMode() {
        Log.v(LOGTAG, "enterFullscreenMode");
        wpeView.enterFullScreen();
    }

    public void exitFullscreenMode() {
        Log.v(LOGTAG, "exitFullscreenMode");
        wpeView.exitFullScreen();
    }

    public void requestExitFullscreenMode() {
        if (pageGlueReady) {
            BrowserGlue.requestExitFullscreenMode(id);
        }
    }

    public boolean canGoBack() { return canGoBack; }

    public boolean canGoForward() { return canGoForward; }

    public void goBack() {
        if (pageGlueReady) {
            BrowserGlue.goBack(id);
        }
    }

    public void goForward() {
        if (pageGlueReady) {
            BrowserGlue.goForward(id);
        }
    }

    public void stopLoading() {
        if (pageGlueReady) {
            BrowserGlue.stopLoading(id);
        }
    }

    public void reload() {
        if (pageGlueReady) {
            BrowserGlue.reload(id);
        }
    }

    public void setInputMethodContent(char c) {
        if (pageGlueReady) {
            BrowserGlue.setInputMethodContent(id, c);
        }
    }

    public void deleteInputMethodContent(int offset) {
        if (pageGlueReady) {
            BrowserGlue.deleteInputMethodContent(id, offset);
        }
    }

    void updateAllSettings() { BrowserGlue.updateAllPageSettings(id, wpeView.getSettings().getPageSettings()); }
}
