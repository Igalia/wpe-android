package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;

/**
 * Interface for handling JavaScript prompt requests. The WPEChromeClient will receive a
 * {@link WPEChromeClient#onJsPrompt(WPEView, String, String, String, WPEJsPromptResult)} call with a
 * WPEJsPromptResult instance as a parameter. This parameter is used to return the result of this user
 * dialog prompt back to the WPEView instance. The client can call cancel() to cancel the dialog or
 * confirm() with the user's input to confirm the dialog.
 */
public interface WPEJsPromptResult extends WPEJsResult {
    void confirm(@NonNull String result);
}
