package com.wpe.wpeview;

import android.view.View;

public interface WPEChromeClient {
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
    default void onShowCustomView(View view, WPEChromeClient.CustomViewCallback callback) {}

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
}
