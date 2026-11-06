package org.wpewebkit.wpeview;

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.Map;

public interface WPEResourceRequest {
    /**
     * Gets the URL for which the resource request was made.
     *
     * @return the URL for which the resource request was made.
     */
    @NonNull
    Uri getUrl();

    /**
     * Gets the method associated with the request, for example "GET".
     *
     * @return the method associated with the request.
     */

    @NonNull
    String getMethod();
    /**
     * Gets the headers associated with the request. These are represented as a mapping of header
     * name to header value.
     *
     * @return the headers associated with the request.
     */
    @NonNull
    Map<String, String> getRequestHeaders();

    /**
     * Gets whether the request is for the main frame of the page, rather than for one of its frames. A navigation
     * that opens a new window is for the main frame of that window.
     *
     * @return whether the request is for the main frame.
     */
    boolean isForMainFrame();

    /**
     * Gets whether the request comes from something the user did in the page, such as tapping a link, rather than
     * from the page by itself, such as a script that changes the address on its own. Scripts that the app runs with
     * {@link WebView#evaluateJavascript} count as user gestures. Not known, and false, for the requests of
     * {@link WebViewClient#onReceivedError} and {@link WebViewClient#onReceivedHttpError}.
     *
     * @return whether the request comes from a user gesture.
     */
    boolean hasGesture();

    /**
     * Gets whether the request follows a redirect from the server.
     *
     * @return whether the request is the result of a server redirect.
     */
    boolean isRedirect();
}
