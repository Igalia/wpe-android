package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WebViewClientOnReceiveHttpErrorTest {
    private HttpServer httpServer;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
    }

    @After
    public void tearDown() {
        if (httpServer != null)
            httpServer.shutdown();
    }

    @Test
    public void testOnReceiveHttpError404() throws Throwable {
        AtomicBoolean received404 = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebViewClient(new WebViewClient() {
                    @Override
                    public void onReceivedHttpError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                                    @NonNull WPEResourceResponse errorResponse) {
                        if (errorResponse.getStatusCode() == HttpResponse.HTTP_NOT_FOUND)
                            received404.set(true);
                        latch.countDown();
                    }
                });

                HttpResponse response = new HttpResponse();
                response.setStatusCode(HttpResponse.HTTP_NOT_FOUND);
                response.setStatusMessage("Not Found");
                response.addHeader("Content-Type", "text/html; charset=utf-8");
                final String url = httpServer.setResponse("/404.html", response);
                activity.getWebView().loadUrl(url);
            })
            .moveToState(Lifecycle.State.RESUMED);

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertTrue(received404.get());
    }
}
