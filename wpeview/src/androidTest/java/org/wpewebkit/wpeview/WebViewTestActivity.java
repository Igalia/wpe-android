package org.wpewebkit.wpeview;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

public class WebViewTestActivity extends Activity {

    private WebView webView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                                   LinearLayout.LayoutParams.MATCH_PARENT));

        webView = new WebView(getApplicationContext());
        webView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                              LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        linearLayout.addView(webView);

        setContentView(linearLayout);
    }

    public WebView getWebView() { return webView; }
}
