package org.wpewebkit.wpe.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CallbackHelper {
    CountDownLatch latch = new CountDownLatch(1);

    public void waitForCallback(long timeout, TimeUnit unit) {
        try {
            latch.await(timeout, unit);
        } catch (InterruptedException e) {
            // Ignore the InterruptedException.
        }
    }

    public void notifyCalled() { latch.countDown(); }
}
