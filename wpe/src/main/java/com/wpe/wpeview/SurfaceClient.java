package com.wpe.wpeview;

import android.view.SurfaceHolder;

public interface SurfaceClient
{
    /**
     * Notify the host application to add a callback used to process Surface creation and update events.
     * @param view The WPEView that initiated the callback.
     * @param callback The SurfaceHolder.Callback2 to let the host application send Surface creation and update events.
     */
    void addCallback(WPEView view, SurfaceHolder.Callback2 callback);
    /**
     * Notify the host application to remove a callback,
     * @param view The WPEView that initiated the callback.
     * @param callback A SurfaceHolder.Callback2 to be removed.
     */
    void removeCallback(WPEView view, SurfaceHolder.Callback2 callback);
}
