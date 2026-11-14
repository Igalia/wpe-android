package org.wpewebkit.wpe.httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class HttpResponse {

    public static final int HTTP_NOT_FOUND = 404;
    // How long a held response waits at most, so that a test that fails does not leave its thread waiting.
    private static final long HELD_SECONDS = 30;

    private int statusCode;
    private String statusMessage;
    private final Map<String, String> headers = new HashMap<>();
    private String bodyText;
    private String body;
    private CountDownLatch held;

    private static final String bodyTemplate = "<html><head><title>%s</title></head><body>%s</body></html>";

    public int getStatusCode() { return statusCode; }

    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public Map<String, String> getHeaders() { return headers; }

    public void addHeader(String name, String value) { headers.put(name, value); }

    public void setBodyText(String bodyText) { this.bodyText = bodyText; }

    /** Sends this page as it is, instead of a page made from the body text with that text as its title. */
    public void setBody(String body) { this.body = body; }

    /** Sends nothing, not even the headers, until the latch opens, as a server that takes its time. */
    public void holdUntil(CountDownLatch released) { held = released; }

    public void send(OutputStream outputStream) throws IOException {
        if (held != null) {
            try {
                held.await(HELD_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        PrintWriter writer = new PrintWriter(outputStream);

        // Write status line
        writer.println("HTTP/1.1 " + statusCode + " " + statusMessage);

        // Write headers
        for (Map.Entry<String, String> header : headers.entrySet()) {
            writer.println(header.getKey() + ": " + header.getValue());
        }

        final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        writer.println("Date: " + dateFormat.format(new Date()));
        // The server closes each connection after its response, so the client must not send another request on it,
        // such as the same request again with credentials.
        writer.println("Connection: close");

        String body = this.body;
        if (body == null && bodyText != null)
            body = String.format(bodyTemplate, bodyText, bodyText);
        if (body != null)
            writer.println("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length);

        // End of headers
        writer.println();

        // Write body
        if (body != null)
            writer.print(body);

        writer.flush();
    }
}
