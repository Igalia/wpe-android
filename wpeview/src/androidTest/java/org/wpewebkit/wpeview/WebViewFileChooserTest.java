package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A file field asks the chrome client for files, and the page gets the files it gives, with their names and
 * contents. A field whose request is cancelled stays as it was, and can ask again.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewFileChooserTest {
    // The page lists the files of the field when they change, with their names and contents.
    private static final String PAGE =
        "<!doctype html><title>File chooser</title>"
        + "<input id=single type=file>"
        + "<input id=multiple type=file multiple accept='image/*,.pdf,text/plain'>"
        + "<script>let changes = 0; let chosen = '';"
        + "for (const input of document.querySelectorAll('input'))"
        + "  input.onchange = async () => {"
        + "    const files = [];"
        + "    for (const file of input.files) files.push(file.name + '=' + await file.text());"
        + "    chosen = files.join(',');"
        + "    changes++;"
        + "  };"
        + "</script>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // Each view has its own web context and processes, and the app can run only a limited number of them.
    @After
    public void tearDown() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
    }

    private final TestWebViewClient webViewClient = new TestWebViewClient();

    private static class FileChooserClient extends WebChromeClient {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<WPECallback<String[]>> callback = new AtomicReference<>();
        final AtomicReference<FileChooserParams> params = new AtomicReference<>();

        @Override
        public boolean onShowFileChooser(@NonNull WebView webView, @NonNull FileChooserParams fileChooserParams,
                                         @NonNull WPECallback<String[]> filePathCallback) {
            callback.set(filePathCallback);
            params.set(fileChooserParams);
            requests.incrementAndGet();
            return true;
        }
    }

    private ActivityScenario<WebViewTestActivity> loadPage(WebChromeClient client) {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(client))
            .moveToState(Lifecycle.State.RESUMED);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, PAGE);
        return scenario;
    }

    private String run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Throwable {
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, script);
    }

    // A script WebView runs counts as a user gesture, which a file field needs to open.
    private void click(ActivityScenario<WebViewTestActivity> scenario, String id) throws Throwable {
        run(scenario, "document.getElementById('" + id + "').click()");
    }

    private static void waitForRequests(FileChooserClient client, int requests) throws InterruptedException {
        for (int i = 0; i < 100 && client.requests.get() < requests; i++)
            Thread.sleep(100);
        Assert.assertEquals("The field did not ask for files", requests, client.requests.get());
    }

    private static File createFile(WebViewTestActivity activity, String name, String content) throws IOException {
        File directory = new File(activity.getCacheDir(), "file-chooser-test");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create " + directory);
        File file = new File(directory, name);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    public void testSingleFieldAsksForOneFileOfAnyType() throws Throwable {
        FileChooserClient client = new FileChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "single");
        waitForRequests(client, 1);
        Assert.assertEquals(WebChromeClient.FileChooserParams.MODE_OPEN, client.params.get().getMode());
        Assert.assertArrayEquals(new String[0], client.params.get().getAcceptTypes());
    }

    @Test
    public void testChosenFilesReachThePage() throws Throwable {
        FileChooserClient client = new FileChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "multiple");
        waitForRequests(client, 1);
        Assert.assertEquals(WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE, client.params.get().getMode());
        Assert.assertArrayEquals(new String[] {"image/*", "text/plain"}, client.params.get().getAcceptTypes());

        AtomicReference<String[]> paths = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                // A name with a percent sign, which WebKit would take for an escape sequence.
                paths.set(new String[] {createFile(activity, "notes 100%25.txt", "first").getAbsolutePath(),
                                        createFile(activity, "second.txt", "second").getAbsolutePath()});
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        scenario.onActivity(activity -> client.callback.get().onResult(paths.get()));
        Assert.assertEquals("1", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "changes", "1"));
        Assert.assertEquals("\"notes 100%25.txt=first,second.txt=second\"", run(scenario, "chosen"));
    }

    @Test
    public void testCancelLeavesTheFieldAsItWas() throws Throwable {
        FileChooserClient client = new FileChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "single");
        waitForRequests(client, 1);
        scenario.onActivity(activity -> client.callback.get().onResult(null));
        // The field asks again once it is answered.
        click(scenario, "single");
        waitForRequests(client, 2);
        Assert.assertEquals("0", run(scenario, "document.getElementById('single').files.length"));
        Assert.assertEquals("0", run(scenario, "changes"));
    }

    @Test
    public void testRelativePathIsRefused() throws Throwable {
        FileChooserClient client = new FileChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "single");
        waitForRequests(client, 1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                client.callback.get().onResult(new String[] {"notes.txt"});
            } catch (IllegalArgumentException exception) {
                thrown.set(exception);
            }
        });
        Assert.assertNotNull("A relative path was taken", thrown.get());
        // The request is still open, and can be answered.
        scenario.onActivity(activity -> client.callback.get().onResult(null));
        click(scenario, "single");
        waitForRequests(client, 2);
    }

    @Test
    public void testAnswerAfterTheFirstIsIgnored() throws Throwable {
        FileChooserClient client = new FileChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "single");
        waitForRequests(client, 1);
        AtomicReference<String> path = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                path.set(createFile(activity, "late.txt", "late").getAbsolutePath());
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            client.callback.get().onResult(null);
            client.callback.get().onResult(new String[] {path.get()});
        });
        Thread.sleep(1000);
        Assert.assertEquals("0", run(scenario, "document.getElementById('single').files.length"));
    }

    @Test
    public void testFieldWithoutClientIsCancelled() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(new WebChromeClient());
        click(scenario, "single");
        // Had the request been left open, the field could not ask again.
        FileChooserClient client = new FileChooserClient();
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(client));
        click(scenario, "single");
        waitForRequests(client, 1);
        Assert.assertEquals("0", run(scenario, "changes"));
    }

    @Test
    public void testAnswerAfterDestroyIsDropped() throws Throwable {
        FileChooserClient client = new FileChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "single");
        waitForRequests(client, 1);
        // Destroying the view cancels the request, and the answer that comes later has nothing to answer.
        scenario.onActivity(activity -> {
            activity.getWebView().destroy();
            client.callback.get().onResult(new String[] {"/nonexistent"});
        });
        TimeUnit.MILLISECONDS.sleep(500);
    }
}
