package com.wpe.wpeview;

public interface WebChromeClient
{
    /**
     * Tell the host application the current progress of loading a page.
     * @param view The WPEView that initiated the callback.
     * @param progress Current page loading progress, represented by
     *                 an integer between 0 and 100.
     */
    default void onProgressChanged(WPEView view, int progress) {}

    /**
     * Notify the host application of a change in the document title.
     * @param view The WPEView that initiated the callback.
     * @param title A String containing the new title of the document.
     */
    default void onReceivedTitle(WPEView view, String title) {}
}
