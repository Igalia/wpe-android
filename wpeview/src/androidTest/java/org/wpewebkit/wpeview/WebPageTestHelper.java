/**
 * Copyright (C) 2026 Igalia S.L.
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

import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Runs pages on the main thread for the tests of {@link WebPage}, and waits for what they report. */
final class WebPageTestHelper {
    static final long TIMEOUT_SECONDS = 30;

    private WebPageTestHelper() {}

    /** Runs the task on the main thread, and throws here what it throws there. */
    static void onMain(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() instanceof RuntimeException)
            throw (RuntimeException)failure.get();
        if (failure.get() instanceof Error)
            throw (Error)failure.get();
    }

    static <T> T onMainGet(Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        onMain(() -> result.set(task.get()));
        return result.get();
    }

    /** A page client that keeps the addresses of the loads that finished and the errors of those that failed. */
    static class RecordingClient extends WebPageClient {
        final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();
        final BlockingQueue<WPEResourceError> errors = new LinkedBlockingQueue<>();

        @Override
        public void onPageFinished(@NonNull WebPage page, @NonNull String url) {
            finishedUrls.add(url);
        }

        @Override
        public void onReceivedError(@NonNull WebPage page, @NonNull WPEResourceRequest request,
                                    @NonNull WPEResourceError error) {
            errors.add(error);
        }

        String waitForFinishedLoad() throws InterruptedException {
            String url = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading", url);
            return url;
        }

        WPEResourceError waitForError() throws InterruptedException {
            WPEResourceError error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The load did not fail", error);
            return error;
        }
    }

    /** Makes a page with a recording client. */
    static WebPage newPage(@NonNull WebContext context, @NonNull RecordingClient client) {
        return onMainGet(() -> {
            WebPage page = new WebPage(context);
            page.setClient(client);
            return page;
        });
    }

    static void loadHtml(@NonNull WebPage page, @NonNull RecordingClient client, @NonNull String html)
        throws InterruptedException {
        client.finishedUrls.clear();
        onMain(() -> page.loadHtml(html, null));
        client.waitForFinishedLoad();
    }

    static String evaluate(@NonNull WebPage page, @NonNull String script) throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        onMain(() -> page.evaluateJavascript(script, result::add));
        String value = result.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The script gave no result: " + script, value);
        return value;
    }

    /**
     * Runs the script until it gives the expected result, for state that WebKit updates later, and returns the last.
     */
    static String waitForResult(@NonNull WebPage page, @NonNull String script, @NonNull String expected)
        throws InterruptedException {
        String result = null;
        for (int i = 0; i < 100; i++) {
            result = evaluate(page, script);
            if (expected.equals(result))
                break;
            Thread.sleep(100);
        }
        return result;
    }

    /** Takes a snapshot with the given call, and returns its bitmap, or null. */
    static Bitmap snapshot(@NonNull Consumer<WebPage.SnapshotCallback> capture) throws InterruptedException {
        BlockingQueue<AtomicReference<Bitmap>> result = new LinkedBlockingQueue<>();
        onMain(() -> capture.accept(bitmap -> result.add(new AtomicReference<>(bitmap))));
        AtomicReference<Bitmap> bitmap = result.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The snapshot did not end", bitmap);
        return bitmap.get();
    }

    static void waitFor(@NonNull String message, @NonNull BooleanSupplier condition) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!onMainGet(condition::getAsBoolean)) {
            assertTrue(message, SystemClock.uptimeMillis() < deadline);
            Thread.sleep(50);
        }
    }

    /** Serves the HTML at the path, and returns its address. */
    static String servePage(@NonNull HttpServer server, @NonNull String path, @NonNull String html) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(html);
        return server.setResponse(path, response);
    }

    /** Loads the address and waits until it finished loading. WebKit adds Back/Forward items for such loads. */
    static void loadUrl(@NonNull WebPage page, @NonNull RecordingClient client, @NonNull String url)
        throws InterruptedException {
        client.finishedUrls.clear();
        onMain(() -> page.loadUrl(url));
        String finished;
        do {
            finished = client.waitForFinishedLoad();
        } while (!finished.equals(url));
    }

    /** A local port where nothing listens, so that connections to it are refused. */
    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
