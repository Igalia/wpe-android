package org.wpewebkit.wpeview;

/**
 * An instance of this interface implementation is passed as a parameter in various {@link WPEChromeClient} action
 * notifications. The object is used as a handle onto the underlying JavaScript-originated request,
 * and provides a means for the client to indicate whether this action should proceed.
 */
public interface WPEJsResult {
    void cancel();

    void confirm();
}
