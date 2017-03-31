package com.wpe.pq;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PQRenderer implements GLSurfaceView.Renderer {
    public PQRenderer()
    {
    }

    @Override
    public void onDrawFrame(GL10 gl)
    {
        GLES20.glClearColor(0.0f, 0.50f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config)
    {
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height)
    {
    }
}
