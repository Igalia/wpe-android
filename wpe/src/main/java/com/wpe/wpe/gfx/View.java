package com.wpe.wpe.gfx;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import androidx.annotation.UiThread;

import com.wpe.wpe.BrowserGlue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

@UiThread
public class View extends GLSurfaceView {
    private static final String LOGTAG = "WPE gfx View";

    private static class Renderer implements GLSurfaceView.Renderer {
        private View m_view;

        private int m_vertexShader = 0;
        private int m_fragmentShader = 0;
        private int m_program = 0;
        private int m_aPosition = 0;
        private int m_aTexture = 0;

        static private String s_vertexShaderSource =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "attribute vec2 pos;\n" +
                        "attribute vec2 texture;\n" +
                        "varying vec2 v_texture;\n" +
                        "void main() {\n" +
                        "  v_texture = texture;\n" +
                        "  gl_Position = vec4(pos, 0, 1);\n" +
                        "}\n";

        static private String s_fragmentShaderSource =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +
                        "uniform samplerExternalOES u_texture;\n" +
                        "varying vec2 v_texture;\n" +
                        "void main() {\n" +
                        "  gl_FragColor = texture2D(u_texture, v_texture);\n" +
                        "}\n";


        static private float[] s_vertices = {
                -1.0f, 1.0f,
                1.0f, 1.0f,
                -1.0f, -1.0f,
                1.0f, -1.0f
        };

        static private float[] s_texturePos = {
                0, 0,
                1, 0,
                0, 1,
                1, 1
        };

        public Renderer(View view) {
            m_view = view;
        }

        static FloatBuffer createFloatBuffer(float[] coords) {
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(coords.length * 4);
            byteBuffer.order(ByteOrder.nativeOrder());
            FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
            floatBuffer.put(coords);
            floatBuffer.position(0);
            return floatBuffer;
        }

        public void onDrawFrame(GL10 gl) {
            boolean surfaceDirty = false;
            synchronized (m_view) {
                surfaceDirty = m_view.m_surfaceDirty;
                m_view.m_surfaceDirty = false;
            }

            if (m_program == 0) {
                m_vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
                GLES20.glShaderSource(m_vertexShader, s_vertexShaderSource);
                GLES20.glCompileShader(m_vertexShader);
                Log.v(LOGTAG, "vertex shader log " + GLES20.glGetShaderInfoLog(m_vertexShader));

                m_fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
                GLES20.glShaderSource(m_fragmentShader, s_fragmentShaderSource);
                GLES20.glCompileShader(m_fragmentShader);
                Log.v(LOGTAG, "fragment shader log " + GLES20.glGetShaderInfoLog(m_fragmentShader));

                m_program = GLES20.glCreateProgram();
                GLES20.glAttachShader(m_program, m_vertexShader);
                GLES20.glAttachShader(m_program, m_fragmentShader);
                GLES20.glLinkProgram(m_program);

                int[] status = new int[1];
                GLES20.glGetProgramiv(m_program, GLES20.GL_LINK_STATUS, status, 0);

                String programLog = GLES20.glGetProgramInfoLog(m_program);
                Log.v(LOGTAG, "program " + m_program + ", status " + status[0] + ", log " + programLog);

                m_aPosition = GLES20.glGetAttribLocation(m_program, "pos");
                m_aTexture = GLES20.glGetAttribLocation(m_program, "texture");
                Log.v(LOGTAG, "attrib locations " + m_aPosition + ", " + m_aTexture);
            }

            if (!surfaceDirty)
                GLES20.glClearColor(0.875f, 0f, 0f, 1.0f);
            else
                GLES20.glClearColor(0f, 0.875f, 0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            if (m_view.m_surfaceTexture != null) {
                GLES20.glUseProgram(m_program);

                m_view.m_surfaceTexture.updateTexImage();

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, m_view.m_textureId);

                GLES20.glVertexAttribPointer(m_aPosition, 2, GLES20.GL_FLOAT, false, 0, createFloatBuffer(s_vertices));
                GLES20.glVertexAttribPointer(m_aTexture, 2, GLES20.GL_FLOAT, false, 0, createFloatBuffer(s_texturePos));

                GLES20.glEnableVertexAttribArray(0);
                GLES20.glEnableVertexAttribArray(1);

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                GLES20.glDisableVertexAttribArray(0);
                GLES20.glDisableVertexAttribArray(1);
            }

            if (surfaceDirty) {
                BrowserGlue.frameComplete();
            }
        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Log.d(LOGTAG, "Renderer::onSurfaceChanged() (" + width + "," + height + ")");
            synchronized (m_view) {
                m_view.m_width = width;
                m_view.m_height = height;
            }
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.v(LOGTAG, "Renderer::onSurfaceCreated() -- unlocked");
            synchronized (m_view) {
                Log.d(LOGTAG, "Renderer::onSurfaceCreated()");
                m_view.m_rendererSurfaceCreated = true;
                m_view.notifyAll();
            }
        }
    }

    private Renderer m_renderer;
    private boolean m_rendererSurfaceCreated = false;
    private int m_textureId = 0;
    private Surface m_surface = null;
    private SurfaceTexture m_surfaceTexture = null;
    private boolean m_surfaceDirty = false;
    private int m_width;
    private int m_height;

    public View(Context context) {
        super(context);

        setEGLContextFactory(new GLSurfaceView.EGLContextFactory() {

            public EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
                int[] attribList = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL10.EGL_NONE
                };

                return egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attribList);
            }

            public void destroyContext(EGL10 egl, EGLDisplay eglDisplay, EGLContext eglContext) {
                egl.eglDestroyContext(eglDisplay, eglContext);
            }

        });

        m_renderer = new Renderer(this);
        setRenderer(m_renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
        requestLayout();
    }

    public void releaseTexture() {
        Log.v(LOGTAG, "Release texture");
        m_surfaceTexture.release();
    }

    @Override
    public void onPause() {
        Log.v(LOGTAG, "onPause()");
        super.onPause();
    }

    @Override
    public void onResume() {
        Log.v(LOGTAG, "onResume()");
        super.onResume();

        requestRender();
    }

    public void ensureSurfaceTexture() {
        Log.d(LOGTAG, "waitForSurfaceCreation()");

        final View view = this;
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

                            m_textureId = textures[0];
                            m_surfaceTexture = new SurfaceTexture(m_textureId);
                            m_surfaceTexture.setDefaultBufferSize(view.m_width, view.m_height);
                            Log.d(LOGTAG, "created texture " + textures[0] + ", surfaceTexture " + m_surfaceTexture);
                            m_surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                                @Override
                                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                                    Log.v(LOGTAG, "new frame available for surfaceTexture " + surfaceTexture);
                                    synchronized (view) {
                                        view.m_surfaceDirty = true;
                                        view.notifyAll();
                                    }
                                    requestRender();
                                }
                            });
                        }
                    }
                });
                while (m_surfaceTexture == null && !view.m_surfaceDirty) {
                    view.wait();
                }
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "WPEView::Renderer creation failed");
            }
        }

        Log.v(LOGTAG, "WPEView::waitForSurfaceCreation() -- done");
    }

    public Surface createSurface() {
        if (m_surfaceTexture == null) {
            Log.e(LOGTAG, "No surface texture");
            return null;
        }
        return new Surface(m_surfaceTexture);
    }

    public int width() {
        return m_width;
    }

    public int height() {
        return m_height;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        if (pointerCount < 1) {
            return false;
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

        BrowserGlue.touchEvent(event.getEventTime(), eventType, event.getX(0), event.getY(0));
        return true;
    }
}
