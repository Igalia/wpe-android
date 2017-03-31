package com.wpe.pq;

import android.app.Activity;
import android.os.Bundle;

public class PQActivity extends Activity {
    private PQView m_view;

    @Override
    protected void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        m_view = new PQView(getApplication());
        setContentView(m_view);
    }

    @Override
    protected void onPause()
    {
        super.onPause();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
    }
}
