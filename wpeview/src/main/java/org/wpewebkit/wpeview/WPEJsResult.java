package org.wpewebkit.wpeview;

/**
 * An instance of this interface implementation is passed as a parameter in various {@link WebChromeClient} action
 * notifications. The object is used as a handle onto the underlying JavaScript-originated request,
 * and provides a means for the client to indicate whether this action should proceed.
 *
 * <p>It can be answered from any thread: the answer reaches the page on the main thread, and only the first answer to
 * get there counts. An answer from another thread comes after the call that asked returns, so an app that answers that
 * way takes the dialog over, returning {@code true}. A callback that throws does not take the dialog over: it is
 * cancelled, unless the callback answered on the main thread before it threw. An answer does nothing once the dialog
 * is cancelled, which happens when the page is destroyed or the WebView that made the page lets go of it.
 */
public interface WPEJsResult {
    void cancel();

    void confirm();
}
