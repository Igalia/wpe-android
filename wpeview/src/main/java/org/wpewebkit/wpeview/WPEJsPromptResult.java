package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;

/**
 * Interface for handling JavaScript prompt requests. The WebChromeClient will receive a
 * {@link WebChromeClient#onJsPrompt(WebView, String, String, String, WPEJsPromptResult)} call with a
 * WPEJsPromptResult instance as a parameter. This parameter is used to return the result of this user
 * dialog prompt back to the WebView instance. The client can call cancel() to cancel the dialog or
 * confirm() with the user's input to confirm the dialog.
 *
 * <p>Like {@link WPEJsResult}, it can be answered from any thread: the answer reaches the page on the main thread, and
 * only the first answer to get there counts.
 */
public interface WPEJsPromptResult extends WPEJsResult {
    void confirm(@NonNull String result);
}
