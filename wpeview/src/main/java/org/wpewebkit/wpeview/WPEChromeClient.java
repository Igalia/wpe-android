/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpeview;

import android.view.View;

import androidx.annotation.NonNull;

public interface WPEChromeClient {
    /**
     * Notify the host application to close the given WebView and remove it
     * from the view system if necessary. At this point, WebCore has stopped
     * any loading in this window and has removed any cross-scripting ability
     * in javascript.
     * <p>
     *
     * @param window The WebView that needs to be closed.
     */

    default void onCloseWindow(@NonNull WPEView window) {}
    /**
     * Tell the host application the current progress while loading a page.
     * @param view The WPEView that initiated the callback.
     * @param progress Current page loading progress, represented by
     * an integer between 0 and 100.
     */
    default void onProgressChanged(@NonNull WPEView view, int progress) {}

    /**
     * Notify the host application of a change in the document title.
     * @param view The WPEView that initiated the callback.
     * @param title A String containing the new title of the document.
     */
    default void onReceivedTitle(@NonNull WPEView view, @NonNull String title) {}

    /**
     * Notify the host application that the URI has changed.
     * @param view the WPEView that initiated the callback.
     * @param uri the new URI.
     */
    default void onUriChanged(@NonNull WPEView view, @NonNull String uri) {}

    /**
     * Notify the host application that the audio playback state has changed.
     * This is called when the WebView starts or stops playing audio.
     * @param view The WPEView that initiated the callback.
     * @param isPlayingAudio {@code true} if the WebView is currently playing audio,
     *                       {@code false} otherwise.
     */
    default void onAudioStateChanged(@NonNull WPEView view, boolean isPlayingAudio) {}

    /**
     * Notify the host application that the muted state has changed.
     * This is called when the WebView is muted or unmuted.
     * @param view The WPEView that initiated the callback.
     * @param isMuted {@code true} if the WebView is currently muted,
     *                {@code false} otherwise.
     */
    default void onMutedStateChanged(@NonNull WPEView view, boolean isMuted) {}

    /**
     * Notify the host application that the camera capture state has changed.
     * This is called when a page starts or stops capturing from the camera.
     * @param view The WPEView that initiated the callback.
     * @param state The capture state. One of:
     *        <ul>
     *        <li>{@code 0} - MEDIA_CAPTURE_STATE_NONE: Camera capture is not active.</li>
     *        <li>{@code 1} - MEDIA_CAPTURE_STATE_ACTIVE: Camera capture is active.</li>
     *        <li>{@code 2} - MEDIA_CAPTURE_STATE_MUTED: Camera capture is muted.</li>
     *        </ul>
     */
    default void onCameraCaptureStateChanged(@NonNull WPEView view, int state) {}

    /**
     * Notify the host application that the microphone capture state has changed.
     * This is called when a page starts or stops capturing from the microphone.
     * @param view The WPEView that initiated the callback.
     * @param state The capture state. One of:
     *        <ul>
     *        <li>{@code 0} - MEDIA_CAPTURE_STATE_NONE: Camera capture is not active.</li>
     *        <li>{@code 1} - MEDIA_CAPTURE_STATE_ACTIVE: Camera capture is active.</li>
     *        <li>{@code 2} - MEDIA_CAPTURE_STATE_MUTED: Camera capture is muted.</li>
     *        </ul>
     */
    default void onMicrophoneCaptureStateChanged(@NonNull WPEView view, int state) {}

    /**
     * Notify the host application that the display capture state has changed.
     * This is called when a page starts or stops screen sharing.
     * @param state The capture state. One of:
     *        <ul>
     *        <li>{@code 0} - MEDIA_CAPTURE_STATE_NONE: Camera capture is not active.</li>
     *        <li>{@code 1} - MEDIA_CAPTURE_STATE_ACTIVE: Camera capture is active.</li>
     *        <li>{@code 2} - MEDIA_CAPTURE_STATE_MUTED: Camera capture is muted.</li>
     *        </ul>
     */
    default void onDisplayCaptureStateChanged(@NonNull WPEView view, int state) {}

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
     * Notify the host application that the current page has entered full screen mode.
     * @param view is the View object to be shown.
     * @param callback invoke this callback to request the page to exit
     * full screen mode.
     */
    default void onShowCustomView(@NonNull View view, @NonNull WPEChromeClient.CustomViewCallback callback) {}

    /**
     * Notify the host application that the current page has exited full screen mode.
     */
    default void onHideCustomView() {}

    /**
     * Notify the host application that the web page wants to display a
     * JavaScript {@code alert()} dialog.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the alert message and suspend
     * JavaScript execution until the dialog is dismissed.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. The app should call
     * {@code WPEJsResult.confirm()} when the custom dialog is dismissed such that
     * JavaScript execution can be resumed.
     * <p>To suppress the dialog and allow JavaScript execution to
     * continue, call {@code WPEJsResult.confirm()} immediately and then return
     * {@code true}.
     * <p>Note that if the {@link WPEChromeClient} is set to be {@code null},
     * or if {@link WPEChromeClient} is not set at all, the default dialog will
     * be suppressed and Javascript execution will continue immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WPEView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A WPEJsResult to confirm that the user closed the window.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WPEView needs to show the default dialog.
     */
    default boolean onJsAlert(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                              @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Notify the host application that the web page wants to display a
     * JavaScript {@code confirm()} dialog.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the message and suspend
     * JavaScript execution until the dialog is dismissed. The default dialog
     * will return {@code true} to the JavaScript {@code confirm()} code when
     * the user presses the 'confirm' button, and will return {@code false} to
     * the JavaScript code when the user presses the 'cancel' button or
     * dismisses the dialog.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. The app should call
     * {@code WPEJsResult.confirm()} or {@code WPEJsResult.cancel()} when the custom
     * dialog is dismissed.
     * <p>To suppress the dialog and allow JavaScript execution to continue,
     * call {@code WPEJsResult.confirm()} or {@code WPEJsResult.cancel()} immediately
     * and then return {@code true}.
     * <p>Note that if the {@link WPEChromeClient} is set to be {@code null},
     * or if {@link WPEChromeClient} is not set at all, the default dialog will
     * be suppressed and the default value of {@code false} will be returned to
     * the JavaScript code immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WPEView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A WPEJsResult used to send the user's response to
     *               javascript.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WPEView needs to show the default dialog.
     */
    default boolean onJsConfirm(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                                @NonNull WPEJsResult result) {
        return false;
    }

    /**
     * Notify the host application that the web page wants to display a
     * JavaScript {@code prompt()} dialog.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the message and suspend
     * JavaScript execution until the dialog is dismissed. Once the dialog is
     * dismissed, JavaScript {@code prompt()} will return the string that the
     * user typed in, or null if the user presses the 'cancel' button.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. The app should call
     * {@code WPEJsPromptResult.confirm(result)} when the custom dialog is
     * dismissed.
     * <p>To suppress the dialog and allow JavaScript execution to continue,
     * call {@code WPEJsPromptResult.confirm(result)} immediately and then
     * return {@code true}.
     * <p>Note that if the {@link WPEChromeClient} is set to be {@code null},
     * or if {@link WPEChromeClient} is not set at all, the default dialog will
     * be suppressed and {@code null} will be returned to the JavaScript code
     * immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WPEView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param defaultValue The default value displayed in the prompt dialog.
     * @param result A WPEJsPromptResult used to send the user's reponse to
     *               javascript.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WPEView needs to show the default dialog.
     */
    default boolean onJsPrompt(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                               @NonNull String defaultValue, @NonNull WPEJsPromptResult result) {
        return false;
    }
    /**
     * Notify the host application that the web page wants to confirm navigation
     * from JavaScript {@code onbeforeunload}.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the message and suspend
     * JavaScript execution until the dialog is dismissed. The default dialog
     * will continue the navigation if the user confirms the navigation, and
     * will stop the navigation if the user wants to stay on the current page.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. When the custom dialog is dismissed, the
     * app should call {@code WPEJsResult.confirm()} to continue the navigation or,
     * {@code WPEJsResult.cancel()} to stay on the current page.
     * <p>To suppress the dialog and allow JavaScript execution to continue,
     * call {@code WPEJsResult.confirm()} or {@code WPEJsResult.cancel()} immediately
     * and then return {@code true}.
     * <p>Note that if the {@link WPEChromeClient} is set to be {@code null},
     * or if {@link WPEChromeClient} is not set at all, the default dialog will
     * be suppressed and the navigation will be resumed immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WPEView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A WPEJsResult used to send the user's response to
     *               javascript.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WPEView needs to show the default dialog.
     */
    default boolean onJsBeforeUnload(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                                     @NonNull WPEJsResult result) {
        return false;
    }
}
