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
            int retryCount = 3;
            int delay = 1000; // Initial delay in milliseconds

            tryToCreateServerSocket(port, retryCount, delay);
        }

        private void tryToCreateServerSocket(int port, int retryCount, int delay) throws Exception {
            while (retryCount > 0) {
                try {
                    serverSocket = new ServerSocket(port);
                    Log.i(TAG, "ServerSocket created successfully on port: " + port);
                    return;
                } catch (IOException e) {
                    retryCount--;
                    Log.w(TAG, "Failed to create ServerSocket (retries left: " + retryCount + "): " + e.getMessage(),
                          e);

                    if (retryCount == 0) {
                        Log.e(TAG, "All retries failed. Throwing exception.");
                        throw e;
                    }

                    Thread.sleep(delay);
                    delay *= 2; // Exponential backoff: double the delay after each retry
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
                    closeSocket(currentRequestSocket);
                }
            }
            cleanUp();
        }

        @Override
        public void run() {
            try {
                while (!isCancelled()) {
                    try {
                        Socket socket = serverSocket.accept();
                        handleSocketConnection(socket);
                    } catch (SocketException e) {
                        if (isCancelled()) {
                            Log.i(TAG, "Server thread cancelled, exiting gracefully.");
                        } else {
                            Log.w(TAG, "SocketException occurred: " + e.getMessage(), e);
                        }
                        break; // Exit loop if server is cancelled or socket error occurs
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "I/O error occurred in server thread: " + e.getMessage(), e);
            } finally {
                cleanUp();
            }
        }

        private void handleSocketConnection(Socket socket) {
            try (InputStream inputStream = socket.getInputStream();
                 PrintStream outputStream = new PrintStream(socket.getOutputStream())) {

                synchronized (lock) { currentRequestSocket = socket; }

                HttpRequest request = HttpRequest.parse(inputStream);
                handleRequest(request, outputStream);

            } catch (HttpRequest.InvalidRequest | IOException e) {
                Log.e(TAG, "Error processing request: " + e.getMessage(), e);
            } finally {
                closeSocket(socket);
            }
        }

        private void closeSocket(Socket socket) {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket: " + e.getMessage(), e);
            }
        }

        private void cleanUp() {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket: " + e.getMessage(), e);
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
