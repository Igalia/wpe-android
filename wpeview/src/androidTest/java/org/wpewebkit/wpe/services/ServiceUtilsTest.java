package org.wpewebkit.wpe.services;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The assets copied out of the APK, such as the GStreamer plugins, match the installed app. This test copies its own
 * folder, test-assets, so it doesn't touch the folders that the WPE processes use.
 */
@RunWith(AndroidJUnit4.class)
public class ServiceUtilsTest {
    private static final String PATH = "test-assets";

    private Context context;
    private AssetManager assetManager;
    private File destDir;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assetManager = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        destDir = new File(context.getFilesDir(), PATH);
        ServiceUtils.deleteRecursively(destDir);
        ServiceUtils.deleteRecursively(new File(context.getFilesDir(), PATH + ".tmp"));
    }

    private static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * After an update, the copy of the old version goes away whole: files that the new version doesn't have, the
     * files derived from them, a copy cut short by a process that died, and the old version markers.
     */
    @Test
    public void testUpdateReplacesTheOldCopy() throws IOException {
        File staleFile = new File(destDir, "removed-in-this-version.so");
        write(staleFile, "old");
        write(new File(destDir, "page"), "old");
        File staleMarker = new File(destDir, ".installed_1");
        write(staleMarker, "");
        File halfCopy = new File(context.getFilesDir(), PATH + ".tmp/not-in-the-assets");
        write(halfCopy, "");
        File derivedFile = new File(context.getCacheDir(), PATH + "-registry.bin");
        write(derivedFile, "built from the old files");
        File oldVersionMarker = new File(context.getFilesDir(), "web_process_2.55.0_gst_GStreamer 1.24.8");
        write(oldVersionMarker, "");

        assertTrue("The assets were not copied",
                   ServiceUtils.installAssets(context, assetManager, PATH, false, derivedFile));

        assertFalse("A file of the old version stayed", staleFile.exists());
        assertFalse("The marker of the old version stayed", staleMarker.exists());
        assertFalse("The copy cut short stayed", new File(destDir, halfCopy.getName()).exists());
        assertFalse("The derived file stayed", derivedFile.exists());
        assertFalse("The old version marker stayed", oldVersionMarker.exists());
        assertEquals("Copied from the APK\n", read(new File(destDir, "page")));
        assertEquals("Nested\n", read(new File(destDir, "folder/nested")));
    }

    /**
     * Later starts of the same install of the app don't copy the assets again.
     */
    @Test
    public void testAssetsAreCopiedOncePerInstall() throws IOException {
        assertTrue("The assets were not copied", ServiceUtils.installAssets(context, assetManager, PATH, false));

        File addedLater = new File(destDir, "added-later.txt");
        write(addedLater, "");
        assertFalse("The assets were copied again", ServiceUtils.installAssets(context, assetManager, PATH, false));
        assertTrue("The copy was replaced", addedLater.exists());
    }

    /**
     * Callers that start at the same time copy the assets once, and none of them goes on before the copy is complete.
     * Here they are threads of one process. Web processes that start together after an update wait on the file lock
     * instead, which this test doesn't reach.
     */
    @Test
    public void testCallersAtTheSameTimeCopyOnce() throws Exception {
        final int callers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(() -> {
                    boolean copied = ServiceUtils.installAssets(context, assetManager, PATH, false);
                    assertEquals("Nested\n", read(new File(destDir, "folder/nested")));
                    return copied;
                });
            }

            int copies = 0;
            for (Future<Boolean> result : executor.invokeAll(calls)) {
                if (result.get())
                    copies++;
            }
            assertEquals("Wrong number of copies", 1, copies);
        } finally {
            executor.shutdown();
        }
    }
}
