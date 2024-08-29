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
}
