package org.wpewebkit.wpeview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;

public class WebViewTestActivity extends Activity {

    private static final String EXTRA_WITHOUT_INPUT_METHOD = "withoutInputMethod";

    private WebView webView;

    /**
     * Starts the activity with a window that Android's input method never connects to, for tests that act as the input
     * method themselves. Otherwise the keyboard gets a connection of its own to the focused field, and what it sends
     * through it, such as finishing the composition when it starts, mixes with what the test sends.
     */
    public static Intent withoutInputMethod(Context context) {
        return new Intent(context, WebViewTestActivity.class).putExtra(EXTRA_WITHOUT_INPUT_METHOD, true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The window still takes the focus, but no longer interacts with the input method.
        if (getIntent().getBooleanExtra(EXTRA_WITHOUT_INPUT_METHOD, false))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

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
