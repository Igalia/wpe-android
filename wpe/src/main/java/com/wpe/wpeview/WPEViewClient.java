package com.wpe.wpeview;

import android.view.View;

public interface WPEViewClient {
    /**
     * Tell the host application the current progress of loading a page.
     *
     * @param view The WPEView that initiated the callback.
     * @param progress Current page loading progress, represented by
     * an integer between 0 and 100.
     */
    default void onProgressChanged(WPEView view, int progress) {}

    /**
     * Notify the host application of a change in the document title.
     *
     * @param view The WPEView that initiated the callback.
     * @param title A String containing the new title of the document.
     */
    default void onReceivedTitle(WPEView view, String title) {}

    /**
     * Notify the host application that the current page has entered full screen mode.
     *
     * @param view is the View object to be shown.
     * @param callback invoke this callback to request the page to exit
     * full screen mode.
     */
    default void onShowCustomView(View view, WPEViewClient.CustomViewCallback callback) {}

    /**
     * Notify the host application that the current page has exited full screen mode.
     */
    default void onHideCustomView() {}

    /**
     * A callback interface used by the host application to notify
     * the current page that its custom view has been dismissed.
     */
    interface CustomViewCallback {
        /**
         * Invoked when the host application dismisses the
         * custom view.
         */
        void onCustomViewHidden();
    }

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
    default void onPageStarted(WPEView view, String url) {}

    /**
     * Notify the host application that a page has finished loading. This method
     * is called only for main frame.
     *
     * @param view The WPEView that is initiating the callback.
     * @param url The url of the page.
     */
    default void onPageFinished(WPEView view, String url) {}

    /**
     * Notify the host application that the internal SurfaceView has been created
     * and it's ready to render to it's surface.
     */
    default void onViewReady(WPEView view) {}
}
