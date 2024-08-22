package com.wpe.wpeview.httpserver;

import android.util.Log;

import androidx.annotation.GuardedBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

public class HttpServer {
    private static final String TAG = "HttpServer";

    private final ServerThread serverThread;
    private String serverUri;

    private final Object lock = new Object();
    private final Map<String, HttpResponse> responseMap = new HashMap<>();

    private HttpServer(int port) throws Exception {
        serverThread = new ServerThread(port);
        setServerHost("localhost");
        serverThread.start();
    }

    public static HttpServer start() throws Exception { return new HttpServer(0); }

    public void shutdown() {
        try {
            serverThread.cancelAllRequests();
            serverThread.join();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setServerHost(String hostname) {
        try {
            serverUri =
                new java.net.URI("http", null, hostname, serverThread.serverSocket.getLocalPort(), null, null, null)
                    .toString();
        } catch (java.net.URISyntaxException e) {
            Log.wtf(TAG, e.getMessage());
        }
    }

    public String setResponse(String requestPath, HttpResponse response) {
        synchronized (lock) { responseMap.put(requestPath, response); }
        return serverUri + requestPath;
    }

    private class ServerThread extends Thread {

        private ServerSocket serverSocket;

        private final Object lock = new Object();

        @GuardedBy("lock")
        private boolean isCancelled;

        @GuardedBy("lock")
        private Socket currentRequestSocket;

        public ServerThread(int port) throws Exception {
            int retry = 3;
            while (true) {
                try {
                    serverSocket = new ServerSocket(port);
                    return;
                } catch (IOException e) {
                    Log.w(TAG, "Http.ServerThread exception: " + e.getMessage());
                    if (--retry == 0) {
                        throw e;
                    }
                    Thread.sleep(1000);
                }
            }
        }

        private boolean isCancelled() {
            synchronized (lock) { return isCancelled; }
        }

        public void cancelAllRequests() throws IOException {
            synchronized (lock) {
                isCancelled = true;
                if (currentRequestSocket != null) {
                    try {
                        currentRequestSocket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            serverSocket.close();
        }

        @Override
        public void run() {
            try {
                while (!isCancelled()) {
                    Socket socket = serverSocket.accept();
                    try (InputStream inputStream = socket.getInputStream();
                         PrintStream outputstream = new PrintStream(socket.getOutputStream())) {
                        synchronized (lock) { currentRequestSocket = socket; }
                        HttpRequest request = HttpRequest.parse(inputStream);
                        handleRequest(request, outputstream);
                    } catch (HttpRequest.InvalidRequest | IOException e) {
                        Log.e(TAG, "" + e.getMessage());
                    }
                }
            } catch (SocketException ignored) {
            } catch (IOException e) {
                Log.w(TAG, "" + e.getMessage());
            }
        }

        private void handleRequest(HttpRequest request, PrintStream stream) throws IOException {
            HttpResponse response;
            synchronized (lock) { response = responseMap.get(request.getURI()); }
            if (response != null) {
                response.send(stream);
            }
        }
    }
}
