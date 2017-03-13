package com.wpe.wpe;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class WPEView extends GLSurfaceView {

    private static class Renderer implements GLSurfaceView.Renderer {

        private WPEView m_view;

        public Renderer(WPEView view)
        {
            m_view = view;
        }

        public void onDrawFrame(GL10 gl) { }
        public void onSurfaceChanged(GL10 gl, int width, int height) { }
        public void onSurfaceCreated(GL10 gl, EGLConfig config)
        {
            Log.i("WPEView", "Renderer::onSurfaceCreated() -- unlocked");
            synchronized (m_view) {
                Log.i("WPEView", "Renderer::onSurfaceCreated()");
                m_view.m_rendererSurfaceCreated = true;
                m_view.notifyAll();
            }
        }
    }

    private Renderer m_renderer;
    private boolean m_rendererSurfaceCreated = false;
    private SurfaceTexture m_surfaceTexture = null;

    public WPEView(Context context)
    {
        super(context);

        m_renderer = new Renderer(this);
        setRenderer(m_renderer);
    }

    public void ensureSurfaceTexture()
    {
        Log.i("WPEView", "waitForSurfaceCreation()");

        final WPEView view = this;
        synchronized (view) {
            try {
                while (!m_rendererSurfaceCreated) {
                    view.wait();
                }

                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (view) {
                            int[] textures = new int[1];
                            GLES20.glGenTextures(1, textures, 0);

                            Log.i("WPEView", "created texture " + textures[0]);
                            m_surfaceTexture = new SurfaceTexture(textures[0]);
                            view.notifyAll();
                        }
                    }
                });
                while (m_surfaceTexture == null) {
                    view.wait();
                }
            } catch (InterruptedException e) {
                Log.i("WPEView", "WPEView::Renderer creation failed");
            }
        }

        Log.i("WPEView", "WPEView::waitForSurfaceCreation() -- done");
    }

    public Surface createSurface()
    {
        return new Surface(m_surfaceTexture);
    }
}
