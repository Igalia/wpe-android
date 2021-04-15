package com.wpe.wpeview;

public class WPEViewClient {
    /**
     * Notify the host application that a page has started loading. This method
     * is called once for each main frame load so a page with iframes or
     * framesets will call onPageStarted one time for the main frame. This also
     * means that onPageStarted will not be called when the contents of an
     * embedded frame changes, i.e. clicking a link whose target is an iframe.
     *
     * @param view The WPEView that is initiating the callback.
     * @param url The url to be loaded.
     */
    public void onPageStarted(WPEView view, String url) {}
    /**
     * Notify the host application that a page has finished loading. This method
     * is called only for main frame.
     *
     * @param view The WPEView that is initiating the callback.
     * @param url The url of the page.
     */
    public void onPageFinished(WPEView view, String url) {}
}
