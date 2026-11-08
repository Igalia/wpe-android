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

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;
import org.wpewebkit.wpe.services.WPEServices;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AuxiliaryProcessTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    /**
     * The network process may end when the last WebContext is destroyed, and the web process of a page ends when its
     * view is. A context made right after gets new ones, in the Android services of those that just ended, which may
     * still be ending. Each page loads. There are more contexts than services of each kind, so the services of the
     * processes that ended must be given to new ones again.
     */
    @Test
    public void testContextsMadeOneAfterAnotherLoadTheirPages() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        // The context of the activity's own view would keep the network process.
        scenario.onActivity(activity -> {
            WebView activityWebView = activity.getWebView();
            ((ViewGroup)activityWebView.getParent()).removeView(activityWebView);
            activityWebView.destroy();
        });
        HttpServer httpServer = HttpServer.start();
        try {
            HttpResponse response = new HttpResponse();
            response.setStatusCode(200);
            response.setStatusMessage("OK");
            response.addHeader("Content-Type", "text/html; charset=utf-8");
            response.setBody("<html><body>page</body></html>");
            String url = httpServer.setResponse("/page", response);

            for (int i = 0; i < WPEServices.MAX_AUX_PROCESSES + 5; i++) {
                CountDownLatch finished = new CountDownLatch(1);
                AtomicReference<WebContext> context = new AtomicReference<>();
                AtomicReference<WebView> webView = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    context.set(new WebContext(activity));
                    webView.set(new WebView(activity, context.get()));
                    webView.get().setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                            finished.countDown();
                        }
                    });
                    activity.addContentView(webView.get(),
                                            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                       ViewGroup.LayoutParams.MATCH_PARENT));
                    webView.get().loadUrl(url);
                });
                boolean loaded = finished.await(20, TimeUnit.SECONDS);
                scenario.onActivity(activity -> {
                    ((ViewGroup)webView.get().getParent()).removeView(webView.get());
                    webView.get().destroy();
                    context.get().destroy();
                });
                assertTrue("The page of context " + (i + 1) + " did not load", loaded);
            }
        } finally {
            httpServer.shutdown();
        }
    }
}
