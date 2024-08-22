package com.wpe.wpeview;

import androidx.annotation.NonNull;

import java.util.Map;

public class WPEResourceResponse {

    private String mimeType;
    private int statusCode;
    private Map<String, String> responseHeaders;

    /**
     * Constructs a resource response with the given parameters.
     *
     * @param mimeType the resource response's MIME type, for example {@code "text/html"}.
     * @param statusCode the status code needs to be in the ranges [100, 299], [400, 599].
     *                   Causing a redirect by specifying a 3xx code is not supported.
     * @param responseHeaders the resource response's headers represented as a mapping of header
     *                        name -> header value.
     */
    public WPEResourceResponse(@NonNull String mimeType, int statusCode, @NonNull Map<String, String> responseHeaders) {
        this.mimeType = mimeType;
        this.statusCode = statusCode;
        this.responseHeaders = responseHeaders;
    }

    /**
     * Sets the resource response's MIME type, for example &quot;text/html&quot;.
     *
     * @param mimeType The resource response's MIME type
     */
    public void setMimeType(@NonNull String mimeType) { this.mimeType = mimeType; }
    /**
     * Gets the resource response's MIME type.
     *
     * @return The resource response's MIME type
     */
    public @NonNull String getMimeType() { return this.mimeType; }

    /**
     * Gets the resource response's status code.
     *
     * @return The resource response's status code.
     */
    public int getStatusCode() { return statusCode; }

    /**
     * Sets the headers for the resource response.
     *
     * @param headers Mapping of header name -> header value.
     */
    public void setResponseHeaders(@NonNull Map<String, String> headers) { this.responseHeaders = headers; }
    /**
     * Gets the headers for the resource response.
     *
     * @return The headers for the resource response.
     */
    public @NonNull Map<String, String> getResponseHeaders() { return responseHeaders; }
}
