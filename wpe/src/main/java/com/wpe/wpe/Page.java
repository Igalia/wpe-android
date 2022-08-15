package com.wpe.wpe;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

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

    private final Context context;
    private final WPEView wpeView;

    private boolean closed = false;

    private final int width;
    private final int height;

    private PageSurfaceView surfaceView;

    private boolean canGoBack = true;
    private boolean canGoForward = true;

    private ScaleGestureDetector scaleDetector;
    private boolean ignoreTouchEvent = false;

    private long nativePtr;
    private native void nativeInit(int width, int height);
    private native void nativeClose();
    private native void nativeDestroy();
    private native void nativeLoadUrl(String url);
    private native void nativeGoBack();
    private native void nativeGoForward();
    private native void nativeStopLoading();
    private native void nativeReload();

    private native void nativeSurfaceCreated(Surface surface);
    private native void nativeSurfaceDestroyed();
    private native void nativeSurfaceChanged(int format, int width, int height);
    private native void nativeSurfaceRedrawNeeded();

    private native void nativeSetZoomLevel(double zoomLevel);

    private native void nativeOnTouchEvent(long time, int type, float x, float y);

    private native void nativeSetInputMethodContent(char c);
    private native void nativeDeleteInputMethodContent(int offset);

    private native void nativeRequestExitFullscreenMode();

    private native void nativeUpdateAllSettings(PageSettings settings);

    public Page(@NonNull Context context, @NonNull WPEView wpeView) {
        LOGTAG = "WPE page";

        Log.v(LOGTAG, "Page construction " + this);

        this.context = context;
        this.wpeView = wpeView;

        width = wpeView.getMeasuredWidth();
        height = wpeView.getMeasuredHeight();

        surfaceView = new PageSurfaceView(context);
        if (wpeView.getSurfaceClient() != null) {
            wpeView.getSurfaceClient().addCallback(wpeView, new PageSurfaceHolderCallback());
        } else {
            SurfaceHolder holder = surfaceView.getHolder();
            Log.d(LOGTAG, "Page surface holder " + holder);
            holder.addCallback(new PageSurfaceHolderCallback());
        }
        surfaceView.requestLayout();

        scaleDetector = new ScaleGestureDetector(context, new PageScaleListener());
    }

    public void init() {
        nativeInit(width, height);

        wpeView.onPageSurfaceViewCreated(surfaceView);
        wpeView.onPageSurfaceViewReady(surfaceView);

        updateAllSettings();
        wpeView.getSettings().getPageSettings().setPage(this);
    }

    public void close() {
        if (closed)
            return;

        closed = true;
        Log.v(LOGTAG, "Page destruction");
        nativeClose();
    }

    public void destroy() {
        close();
        nativeDestroy();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    public void loadUrl(@NonNull Context context, @NonNull String url) {
        Log.d(LOGTAG, "loadUrl " + url);
        nativeLoadUrl(url);
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
        imm.hideSoftInputFromWindow(surfaceView.getWindowToken(), 0);
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

    public void requestExitFullscreenMode() { nativeRequestExitFullscreenMode(); }

    public boolean canGoBack() { return canGoBack; }

    public boolean canGoForward() { return canGoForward; }

    public void goBack() { nativeGoBack(); }

    public void goForward() { nativeGoForward(); }

    public void stopLoading() { nativeStopLoading(); }

    public void reload() { nativeReload(); }

    public void setInputMethodContent(char c) { nativeSetInputMethodContent(c); }

    public void deleteInputMethodContent(int offset) { nativeDeleteInputMethodContent(offset); }

    void updateAllSettings() { nativeUpdateAllSettings(wpeView.getSettings().getPageSettings()); }

    private class PageSurfaceHolderCallback implements SurfaceHolder.Callback2 {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceCreated()");
            nativeSurfaceCreated(holder.getSurface());
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceDestroyed()");
            nativeSurfaceDestroyed();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(LOGTAG,
                  "PageSurfaceHolderCallback::surfaceChanged() format " + format + " (" + width + "," + height + ")");

            nativeSurfaceChanged(format, width, height);
        }

        @Override
        public void surfaceRedrawNeeded(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceRedrawNeeded()");
            nativeSurfaceRedrawNeeded();
        }
    }

    private class PageScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float m_scaleFactor = 1.f;

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            Log.d(LOGTAG, "PageScaleListener::onScale()");

            m_scaleFactor *= detector.getScaleFactor();

            m_scaleFactor = Math.max(0.1f, Math.min(m_scaleFactor, 5.0f));

            nativeSetZoomLevel(m_scaleFactor);

            ignoreTouchEvent = true;

            return true;
        }
    }

    public class PageSurfaceView extends SurfaceView {
        public PageSurfaceView(Context context) { super(context); }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int pointerCount = event.getPointerCount();
            if (pointerCount < 1) {
                return false;
            }

            scaleDetector.onTouchEvent(event);

            if (ignoreTouchEvent) {
                ignoreTouchEvent = false;
            }

            int eventType;

            int eventAction = event.getActionMasked();
            switch (eventAction) {
            case MotionEvent.ACTION_DOWN:
                eventType = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                eventType = 1;
                break;
            case MotionEvent.ACTION_UP:
                eventType = 2;
                break;
            default:
                return false;
            }

            nativeOnTouchEvent(event.getEventTime(), eventType, event.getX(0), event.getY(0));
            return true;
        }
    }
}
