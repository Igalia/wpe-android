package com.wpe.wpeview.httpserver;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

public class HttpRequest {

    public static class InvalidRequest extends Exception {
        /** Constructor */
        public InvalidRequest() { super("Invalid HTTP request"); }
    }

    private final String method;
    private final String uri;
    private final String httpVersion;
    private final Map<String, String> headers;
    private final String body;

    public HttpRequest(String method, String uri, String httpVersion, Map<String, String> headers, String body) {
        this.method = method;
        this.uri = uri;
        this.httpVersion = httpVersion;
        this.headers = headers;
        this.body = body;
    }

    public String getMethod() { return method; }

    public String getURI() { return uri; }

    public String getHttpVersion() { return httpVersion; }

    public Map<String, String> getHeaders() { return headers; }

    public String getBody() { return body; }

    public static HttpRequest parse(InputStream inputStream) throws InvalidRequest, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

        // Parse request line
        String requestLine = reader.readLine();
        if (requestLine == null) {
            throw new InvalidRequest(); // End of stream reached, invalid request
        }

        String[] requestLineParts = requestLine.split(" ");
        if (requestLineParts.length < 3) {
            throw new InvalidRequest(); // Malformed request line
        }

        String method = requestLineParts[0];
        String uri = requestLineParts[1];
        String httpVersion = requestLineParts[2];

        // Parse headers
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            String[] headerParts = line.split(": ", 2);
            if (headerParts.length == 2) {
                headers.put(headerParts[0], headerParts[1]);
            }
        }

        // Parse body
        StringBuilder body = new StringBuilder();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            String contentLengthHeader = headers.get("Content-Length");
            if (contentLengthHeader != null) {
                int contentLength = Integer.parseInt(contentLengthHeader);
                char[] bodyChars = new char[contentLength];
                reader.read(bodyChars, 0, contentLength);
                body.append(bodyChars);
            }
        }

        return new HttpRequest(method, uri, httpVersion, headers, body.toString());
    }
}
