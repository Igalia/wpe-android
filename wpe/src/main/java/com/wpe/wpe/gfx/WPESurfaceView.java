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
public class WPESurfaceView extends SurfaceView
{
    private String LOGTAG;

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener
    {
        @Override
        public boolean onScale(ScaleGestureDetector detector)
        {
            mScaleFactor *= detector.getScaleFactor();

            mScaleFactor = Math.max(0.1f, Math.min(mScaleFactor, 5.0f));

            BrowserGlue.setZoomLevel(m_pageId, mScaleFactor);

            m_ignoreTouchEvent = true;

            return true;
        }
    }

    private static class SurfaceHolderCallback implements SurfaceHolder.Callback2
    {
        private WPESurfaceView m_view;

        SurfaceHolderCallback(WPESurfaceView view)
        {
            m_view = view;
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
        {
            Log.d(m_view.LOGTAG, "SurfaceHolderCallback::surfaceChanged() format " + format + " (" + width + "," + height + ")");
            synchronized (m_view) {
                m_view.m_width = width;
                m_view.m_height = height;
            }

            BrowserGlue.surfaceChanged(m_view.m_pageId, format, width, height);
        }

        public void surfaceCreated(SurfaceHolder holder)
        {
            Log.d(m_view.LOGTAG, "SurfaceHolderCallback::surfaceCreated()");
            BrowserGlue.surfaceCreated(m_view.m_pageId, holder.getSurface());
        }

        public void surfaceDestroyed(SurfaceHolder holder)
        {
            Log.d(m_view.LOGTAG, "SurfaceHolderCallback::surfaceDestroyed()");
            BrowserGlue.surfaceDestroyed(m_view.m_pageId);
        }

        public void surfaceRedrawNeeded(SurfaceHolder holder)
        {
            Log.d(m_view.LOGTAG, "SurfaceHolderCallback::surfaceRedrawNeeded()");
            BrowserGlue.surfaceRedrawNeeded(m_view.m_pageId);
        }
    }

    private final int m_pageId;
    private int m_width;
    private int m_height;
    private ScaleGestureDetector mScaleDetector;
    private float mScaleFactor = 1.f;
    private boolean m_ignoreTouchEvent = false;

    public WPESurfaceView(Context context, int pageId, WPEView wpeView)
    {
        super(context);

        LOGTAG = "WPE gfx.WPESurfaceView" + pageId;

        m_pageId = pageId;

        if (wpeView.getSurfaceClient() != null) {
            wpeView.getSurfaceClient().addCallback(wpeView, new SurfaceHolderCallback(this));
        } else {
            SurfaceHolder holder = getHolder();
            Log.d(LOGTAG, "WPESurfaceView: holder " + holder);
            holder.addCallback(new SurfaceHolderCallback(this));
        }

        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());

        requestLayout();
    }

    public int width()
    {
        return m_width;
    }

    public int height()
    {
        return m_height;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        int pointerCount = event.getPointerCount();
        if (pointerCount < 1) {
            return false;
        }

        mScaleDetector.onTouchEvent(event);

        if (m_ignoreTouchEvent) {
            m_ignoreTouchEvent = false;
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

        BrowserGlue.touchEvent(m_pageId, event.getEventTime(), eventType, event.getX(0), event.getY(0));
        return true;
    }
}