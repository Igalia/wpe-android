package com.wpe.wpe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class WPEActivity extends Activity {

    public WPEView m_view;
    private Thread m_thread;
    private WPEUIProcessGlue m_glueObj;

    @Override protected void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        Log.i("WPE", "Hello there");

        m_view = new WPEView(getApplication());
        setContentView(m_view);

        final WPEActivity activity = this;
        m_thread = new Thread(new Runnable() {
            @Override
            public void run()
            {
                Log.i("WPEActivity", "m_thread.run()");
                m_view.ensureSurfaceTexture();

                m_glueObj = new WPEUIProcessGlue(activity);
                WPEUIProcessGlue.init(m_glueObj);
            }
        }, "WPEActivityThread");
        m_thread.start();
    }

    @Override protected void onPause()
    {
        super.onPause();
        m_view.onPause();
    }

    @Override protected void onResume()
    {
        super.onResume();
        m_view.onResume();
    }

    @Override protected void onDestroy()
    {
        synchronized (m_thread) {
            WPEUIProcessGlue.deinit();
        }

        super.onDestroy();
    }
}
