/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

package com.wpe.wpe;

import android.annotation.SuppressLint;
import android.content.Context;
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

import com.wpe.wpeview.WPEView;

/**
 * A Page roughly corresponds with a tab in a regular browser UI.
 * There is a 1:1 relationship between WPEView and Page.
 * Each Page instance has its own wpe.wpe.gfx.View and WebKitWebView instances associated.
 * It also keeps references to the Services that host the logic of WebKit's auxiliary
 * processes (WebProcess and NetworkProcess).
 */
@UiThread
public final class Page {
    private static final String LOGTAG = "WPEPage";

    public static final int LOAD_STARTED = 0;
    public static final int LOAD_REDIRECTED = 1;
    public static final int LOAD_COMMITTED = 2;
    public static final int LOAD_FINISHED = 3;

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    private native long nativeInit(int width, int height);
    private native void nativeClose(long nativePtr);
    private native void nativeDestroy(long nativePtr);
    private native void nativeLoadUrl(long nativePtr, @NonNull String url);
    private native void nativeGoBack(long nativePtr);
    private native void nativeGoForward(long nativePtr);
    private native void nativeStopLoading(long nativePtr);
    private native void nativeReload(long nativePtr);
    protected native void nativeSurfaceCreated(long nativePtr, @NonNull Surface surface);
    protected native void nativeSurfaceChanged(long nativePtr, int format, int width, int height);
    protected native void nativeSurfaceRedrawNeeded(long nativePtr);
    protected native void nativeSurfaceDestroyed(long nativePtr);
    protected native void nativeSetZoomLevel(long nativePtr, double zoomLevel);
    protected native void nativeOnTouchEvent(long nativePtr, long time, int type, float x, float y);
    private native void nativeSetInputMethodContent(long nativePtr, int unicodeChar);
    private native void nativeDeleteInputMethodContent(long nativePtr, int offset);
    private native void nativeRequestExitFullscreenMode(long nativePtr);

    private final WPEView wpeView;
    private final int width;
    private final int height;
    private final PageSurfaceView surfaceView;
    protected final ScaleGestureDetector scaleDetector;
    private final PageSettings pageSettings;

    public @NonNull PageSettings getPageSettings() { return pageSettings; }

    private boolean isClosed = false;
    private boolean canGoBack = true;
    private boolean canGoForward = true;
    protected boolean ignoreTouchEvents = false;

    public Page(@NonNull WPEView wpeView) {
        Log.v(LOGTAG, "Creating Page: " + this);

        this.wpeView = wpeView;
        width = wpeView.getMeasuredWidth();
        height = wpeView.getMeasuredHeight();
        nativePtr = nativeInit(width, height);

        Context ctx = wpeView.getContext();
        surfaceView = new PageSurfaceView(ctx);
        if (wpeView.getSurfaceClient() != null) {
            wpeView.getSurfaceClient().addCallback(wpeView, new PageSurfaceHolderCallback());
        } else {
            SurfaceHolder holder = surfaceView.getHolder();
            Log.d(LOGTAG, "Page: " + this + " surface holder: " + holder);
            holder.addCallback(new PageSurfaceHolderCallback());
        }
        surfaceView.requestLayout();

        scaleDetector = new ScaleGestureDetector(ctx, new PageScaleListener());

        wpeView.onPageSurfaceViewCreated(surfaceView);
        wpeView.onPageSurfaceViewReady(surfaceView);

        pageSettings = new PageSettings(this);
    }

    public void close() {
        if (!isClosed) {
            isClosed = true;
            Log.v(LOGTAG, "Closing Page: " + this);
            nativeClose(nativePtr);
        }
    }

    public void destroy() {
        close();
        nativeDestroy(nativePtr);
        nativePtr = 0;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    public void loadUrl(@NonNull String url) {
        Log.d(LOGTAG, "loadUrl('" + url + "')");
        nativeLoadUrl(nativePtr, url);
    }

    @Keep
    public void onLoadChanged(int loadEvent) {
        wpeView.onLoadChanged(loadEvent);
        if (loadEvent == Page.LOAD_STARTED) {
            onInputMethodContextOut();
        }
    }

    @Keep
    public void onLoadProgress(double progress) {
        wpeView.onLoadProgress(progress);
    }

    @Keep
    public void onUriChanged(@NonNull String uri) {
        wpeView.onUriChanged(uri);
    }

    @Keep
    public void onTitleChanged(@NonNull String title, boolean canGoBack, boolean canGoForward) {
        this.canGoBack = canGoBack;
        this.canGoForward = canGoForward;
        wpeView.onTitleChanged(title);
    }

    @Keep
    public void onInputMethodContextIn() {
        InputMethodManager imm =
            (InputMethodManager)wpeView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(wpeView, 0);
    }

    @Keep
    public void onInputMethodContextOut() {
        InputMethodManager imm =
            (InputMethodManager)wpeView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(surfaceView.getWindowToken(), 0);
    }

    @Keep
    public void onEnterFullscreenMode() {
        Log.d(LOGTAG, "onEnterFullscreenMode()");
        wpeView.onEnterFullscreenMode();
    }

    @Keep
    public void onExitFullscreenMode() {
        Log.d(LOGTAG, "onExitFullscreenMode()");
        wpeView.onExitFullscreenMode();
    }

    public void requestExitFullscreenMode() { nativeRequestExitFullscreenMode(nativePtr); }

    public boolean canGoBack() { return canGoBack; }

    public boolean canGoForward() { return canGoForward; }

    public void goBack() { nativeGoBack(nativePtr); }

    public void goForward() { nativeGoForward(nativePtr); }

    public void stopLoading() { nativeStopLoading(nativePtr); }

    public void reload() { nativeReload(nativePtr); }

    public void setInputMethodContent(int unicodeChar) { nativeSetInputMethodContent(nativePtr, unicodeChar); }

    public void deleteInputMethodContent(int offset) { nativeDeleteInputMethodContent(nativePtr, offset); }

    protected final class PageSurfaceHolderCallback implements SurfaceHolder.Callback2 {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceCreated()");
            nativeSurfaceCreated(nativePtr, holder.getSurface());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceChanged() with format: " + format + " and size: " + width +
                              " x " + height);
            nativeSurfaceChanged(nativePtr, format, width, height);
        }

        @Override
        public void surfaceRedrawNeeded(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceRedrawNeeded()");
            nativeSurfaceRedrawNeeded(nativePtr);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceDestroyed()");
            nativeSurfaceDestroyed(nativePtr);
        }
    }

    protected final class PageScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float m_scaleFactor = 1.f;

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            Log.d(LOGTAG, "PageScaleListener::onScale()");

            m_scaleFactor *= detector.getScaleFactor();
            m_scaleFactor = Math.max(0.1f, Math.min(m_scaleFactor, 5.0f));
            nativeSetZoomLevel(nativePtr, m_scaleFactor);

            ignoreTouchEvents = true;
            return true;
        }
    }

    private final class PageSurfaceView extends SurfaceView {
        public PageSurfaceView(Context context) { super(context); }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent event) {
            int pointerCount = event.getPointerCount();
            if (pointerCount < 1)
                return false;

            scaleDetector.onTouchEvent(event);
            if (ignoreTouchEvents)
                ignoreTouchEvents = false;

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

            nativeOnTouchEvent(nativePtr, event.getEventTime(), eventType, event.getX(0), event.getY(0));
            return true;
        }
    }
}
