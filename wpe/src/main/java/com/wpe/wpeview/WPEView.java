package com.wpe.wpeview;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.wpe.wpe.Browser;

/**
 * WPEView wraps WPE WebKit browser engine in a reusable Android library.
 * WPEView serves a similar purpose to Android's built-in WebView and tries to mimick
 * its API aiming to be an easy to use drop-in replacement with extended functionality.
 *
 * The WPEView class is the main API entry point.
 */
@UiThread
public class WPEView extends FrameLayout {
    private static final String LOGTAG = "WPEView";
    private final Context m_context;

    public WPEView(final Context context) {
        super(context);
        m_context = context;
    }

    public WPEView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        m_context = context;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        addView(Browser.getInstance().createPage(this, m_context));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Browser.getInstance().destroyPage(this);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        Browser.getInstance().onVisibilityChanged(this, visibility);
    }

    /**
     * Loads the given URL.
     * @param url The URL of the resource to be loaded.
     */
    public void loadUrl(@NonNull String url) {
        Browser.getInstance().loadUrl(this, url);
    }
}
