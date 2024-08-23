package com.wpe.wpeview;

import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.runner.AndroidJUnit4;

import com.wpe.wpeview.httpserver.HttpResponse;
import com.wpe.wpeview.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class WPEViewClientOnReceiveHttpErrorTest {
    private HttpServer httpServer;

    @Rule
    public ActivityScenarioRule<WPEViewTestActivity> wpeViewActivityTestRule =
        new ActivityScenarioRule<>(WPEViewTestActivity.class);

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
    public void testOnReceiveHttpError404() {
        AtomicBoolean received404 = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        ActivityScenario<WPEViewTestActivity> scenario = wpeViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWPEView().setWPEViewClient(new WPEViewClient() {
                    @Override
                    public void onReceivedHttpError(@NonNull WPEView view, @NonNull WPEResourceRequest request,
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
                activity.getWPEView().loadUrl(url);
            })
            .moveToState(Lifecycle.State.RESUMED);

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertTrue(received404.get());
    }
}
