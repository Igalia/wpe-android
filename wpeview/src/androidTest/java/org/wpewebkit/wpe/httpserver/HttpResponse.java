package org.wpewebkit.wpe.httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HttpResponse {

    public static final int HTTP_NOT_FOUND = 404;

    private int statusCode;
    private String statusMessage;
    private final Map<String, String> headers = new HashMap<>();
    private String bodyText;

    private static final String bodyTemplate = "<html><head><title>%s</title></head><body>%s</body></html>";

    public int getStatusCode() { return statusCode; }

    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public Map<String, String> getHeaders() { return headers; }

    public void addHeader(String name, String value) { headers.put(name, value); }

    public void setBodyText(String bodyText) { this.bodyText = bodyText; }

    public void send(OutputStream outputStream) throws IOException {
        PrintWriter writer = new PrintWriter(outputStream);

        // Write status line
        writer.println("HTTP/1.1 " + statusCode + " " + statusMessage);

        // Write headers
        for (Map.Entry<String, String> header : headers.entrySet()) {
            writer.println(header.getKey() + ": " + header.getValue());
        }

        final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        writer.print("Date: " + dateFormat.format(new Date()));

        // End of headers
        writer.println();

        // Write body
        if (bodyText != null) {
            String body = String.format(bodyTemplate, bodyText, bodyText);
            addHeader("Content-Length", Integer.toString(body.length()));
            writer.print(body);
        } else {
            writer.println();
        }

        writer.flush();
    }
}
