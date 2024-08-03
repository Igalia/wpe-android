package com.wpe.wpeview;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;

public class WPEViewTestActivity extends Activity {

    private WPEView wpeView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                                   LinearLayout.LayoutParams.MATCH_PARENT));

        wpeView = new WPEView(getApplicationContext());
        wpeView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                              LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        linearLayout.addView(wpeView);

        setContentView(linearLayout);
    }

    public WPEView getWPEView() { return wpeView; }
}
