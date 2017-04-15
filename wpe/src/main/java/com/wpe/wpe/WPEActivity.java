package com.wpe.wpe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.wpe.wpe.UIProcess.Glue;

public class WPEActivity extends Activity {

    private Glue m_glue;
    public WPEView m_view;
    private Thread m_thread;

    @Override protected void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        Log.i("WPE", "Hello there");

        m_glue = new Glue(this);

        Log.i("WPE", "cache dir " + getBaseContext().getCacheDir());

        m_view = new WPEView(getApplication());
        setContentView(m_view);

        final WPEActivity activity = this;
        m_thread = new Thread(new Runnable() {
            @Override
            public void run()
            {
                Log.i("WPEActivity", "m_thread.run()");
                m_view.ensureSurfaceTexture();

                Glue.init(m_glue, m_view.width(), m_view.height());
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
            Glue.deinit();
        }

        super.onDestroy();
    }
}
