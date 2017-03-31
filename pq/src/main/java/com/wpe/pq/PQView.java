package com.wpe.pq;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.MotionEvent;

public class PQView extends GLSurfaceView {
    private PQRenderer m_renderer;

    public PQView(Context context)
    {
        super(context);

        m_renderer = new PQRenderer();
        setRenderer(m_renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        Log.i("PQView", "onTouchEvent(): " + event.toString());
        return true;
    }
}
