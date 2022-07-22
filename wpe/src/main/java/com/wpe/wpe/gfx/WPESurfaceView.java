package com.wpe.wpe.gfx;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.UiThread;

import com.wpe.wpe.BrowserGlue;
import com.wpe.wpeview.WPEView;

@UiThread
public class WPESurfaceView extends SurfaceView {
    private final int pageId;
    private final String LOGTAG;
    private final ScaleGestureDetector scaleDetector;

    private int width;
    private int height;

    private float scaleFactor = 1.f;
    private boolean ignoreTouchEvent = false;

    public WPESurfaceView(Context context, int pageId, WPEView wpeView) {
        super(context);

        LOGTAG = "WPE gfx.WPESurfaceView" + pageId;

        this.pageId = pageId;

        if (wpeView.getSurfaceClient() != null) {
            wpeView.getSurfaceClient().addCallback(wpeView, new SurfaceHolderCallback(this));
        } else {
            SurfaceHolder holder = getHolder();
            Log.d(LOGTAG, "WPESurfaceView: holder " + holder);
            holder.addCallback(new SurfaceHolderCallback(this));
        }

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());

        requestLayout();
    }

    public int width() { return width; }

    public int height() { return height; }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        if (pointerCount < 1)
            return false;

        scaleDetector.onTouchEvent(event);

        if (ignoreTouchEvent)
            ignoreTouchEvent = false;

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

        BrowserGlue.touchEvent(pageId, event.getEventTime(), eventType, event.getX(0), event.getY(0));
        return true;
    }

    private static class SurfaceHolderCallback implements SurfaceHolder.Callback2 {
        private final WPESurfaceView view;

        SurfaceHolderCallback(WPESurfaceView view) { this.view = view; }

        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(view.LOGTAG,
                  "SurfaceHolderCallback::surfaceChanged() format " + format + " (" + width + "," + height + ")");
            synchronized (view) {
                view.width = width;
                view.height = height;
            }

            BrowserGlue.surfaceChanged(view.pageId, format, width, height);
        }

        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(view.LOGTAG, "SurfaceHolderCallback::surfaceCreated()");
            BrowserGlue.surfaceCreated(view.pageId, holder.getSurface());
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(view.LOGTAG, "SurfaceHolderCallback::surfaceDestroyed()");
            BrowserGlue.surfaceDestroyed(view.pageId);
        }

        public void surfaceRedrawNeeded(SurfaceHolder holder) {
            Log.d(view.LOGTAG, "SurfaceHolderCallback::surfaceRedrawNeeded()");
            BrowserGlue.surfaceRedrawNeeded(view.pageId);
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 5.0f));
            BrowserGlue.setZoomLevel(pageId, scaleFactor);

            ignoreTouchEvent = true;
            return true;
        }
    }
}
