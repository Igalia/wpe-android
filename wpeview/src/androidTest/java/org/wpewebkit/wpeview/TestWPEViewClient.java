package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.util.CallbackHelper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestWPEViewClient extends WPEViewClient {

    private final CallbackHelper onPageFinishedHelper;
    private final OnEvaluateJavaScriptResultHelper onEvaluateJavascriptResultHelper;

    public TestWPEViewClient() {
        onPageFinishedHelper = new CallbackHelper();
        onEvaluateJavascriptResultHelper = new OnEvaluateJavaScriptResultHelper();
    }

    CallbackHelper getOnPageFinishedHelper() { return onPageFinishedHelper; }
    OnEvaluateJavaScriptResultHelper getOnEvaluateJavascriptResultHelper() { return onEvaluateJavascriptResultHelper; }

    public static class OnEvaluateJavaScriptResultHelper extends CallbackHelper {
        private String result;
        public void evaluateJavascript(WPEView view, String script) {
            view.evaluateJavascript(script, this::notifyCalled);
        }

        public boolean hasValue() { return result != null; }

        public String getResultAndClear() {
            assert hasValue();
            String res = result;
            result = null;
            return res;
        }

        public boolean waitUntilHasValue(long timeout, TimeUnit unit) throws TimeoutException {
            // Reads and writes are atomic for reference variables in java, this is thread safe
            if (hasValue())
                return true;
            waitForCallback(timeout, unit);
            return hasValue();
        }

        private void notifyCalled(String result) {
            this.result = result;
            notifyCalled();
        }
    }

    @Override
    public void onPageFinished(@NonNull WPEView view, @NonNull String url) {
        onPageFinishedHelper.notifyCalled();
    }
}
