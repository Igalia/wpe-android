/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

package org.wpewebkit.wpe.services;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WPEVersions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

public final class ServiceUtils {
    private static final String LOGTAG = "ServiceUtils";

    // Version markers of the copies made before installAssets(), such as "web_process_2.55.0_gst_GStreamer 1.28.8".
    private static final Pattern OLD_MARKER_NAME = Pattern.compile("(ui|web|network|webdriver)_process_.*_gst_.*");

    private static @Nullable String installId = null;

    /**
     * Copies a folder of assets from the APK to the files folder, once per install or update of the app, because the
     * native code needs real files. The path is a folder at the top of the assets, and it keeps its name in the files
     * folder. Returns whether it copied the folder.
     *
     * An update replaces the whole folder, so that files that the new version no longer has don't stay behind. The
     * derived files, such as the GStreamer plugin registry, are deleted with it.
     *
     * Several processes of the app can install the same folder at once, and any of them can die halfway. A file lock
     * lets one process copy while the others wait, and the copy only takes the folder's name, with its version marker
     * inside, once it is complete and on disk.
     */
    public static synchronized boolean installAssets(@NonNull Context context, @NonNull AssetManager assetManager,
                                                     @NonNull String path, boolean copyForCurrentABI,
                                                     @NonNull File... derivedFiles) {
        File filesDir = context.getFilesDir();
        File destDir = new File(filesDir, path);
        File marker = new File(destDir, ".installed_" + getInstallId(context));
        if (marker.exists())
            return false;

        try (RandomAccessFile lockFile = new RandomAccessFile(new File(filesDir, path + ".lock"), "rw");
             FileLock lock = lockFile.getChannel().lock()) {
            if (marker.exists())
                return false;

            Log.i(LOGTAG, "Installing assets into " + destDir);
            File tempDir = new File(filesDir, path + ".tmp");
            deleteRecursively(tempDir);
            for (File file : derivedFiles)
                deleteRecursively(file);
            deleteRecursively(destDir);

            String srcAsset = findAssets(assetManager, path, copyForCurrentABI);
            if (srcAsset == null)
                return false;
            try {
                recCopyFileOrDir(assetManager, srcAsset, tempDir);
                new File(tempDir, marker.getName()).createNewFile();
                Files.move(tempDir.toPath(), destDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Frees the space of the half copy right away, since a full disk is a likely cause. This happens while
                // holding the lock, so it can't delete the copy of another process.
                try {
                    deleteRecursively(tempDir);
                } catch (IOException deleteError) {
                    e.addSuppressed(deleteError);
                }
                throw e;
            }

            deleteOldMarkers(filesDir);
            return true;
        } catch (IOException e) {
            Log.e(LOGTAG, "Cannot install assets into " + destDir, e);
            return false;
        }
    }

    // Changes whenever the app can bring other assets. The install time changes on every install or update, even
    // when the versions stay the same. Apps that come with the system are updated with it and can keep their install
    // time and version code, so for them the build of the system is part of the id too.
    @SuppressWarnings("deprecation")
    private static @NonNull String getInstallId(@NonNull Context context) {
        if (installId != null)
            return installId;

        String id = WPEVersions.WEBKIT + "_" + WPEVersions.GSTREAMER_MAJOR + "." + WPEVersions.GSTREAMER_MINOR + "." +
                    WPEVersions.GSTREAMER_MICRO;
        PackageManager packageManager = context.getPackageManager();
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo =
                    packageManager.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            }
            id = packageInfo.getLongVersionCode() + "_" + packageInfo.lastUpdateTime + "_" + id;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOGTAG, "Cannot find when the app was installed, so only the versions tell when to copy assets", e);
        }
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            // The id goes in a file name, and the fingerprint has slashes.
            id += "_" + Build.FINGERPRINT.replaceAll("[^A-Za-z0-9._]", "_");
        }
        installId = id;
        return id;
    }

    private static @Nullable String findAssets(@NonNull AssetManager assetManager, @NonNull String path,
                                               boolean copyForCurrentABI) {
        if (!copyForCurrentABI)
            return path;

        for (String abi : Build.SUPPORTED_ABIS) {
            String testPath = path + File.separator + abi;
            try {
                if (assetManager.list(testPath).length > 0)
                    return testPath;
            } catch (IOException e) {
            }
        }

        Log.e(LOGTAG, "Cannot find " + path + " assets for current ABI");
        return null;
    }

    private static void recCopyFileOrDir(@NonNull AssetManager assetManager, @NonNull String srcAsset,
                                         @NonNull File destFile) throws IOException {
        String[] assets = assetManager.list(srcAsset);
        if (assets.length == 0)
            copyFile(assetManager, srcAsset, destFile);
        else {
            for (String asset : assets)
                recCopyFileOrDir(assetManager, srcAsset + File.separator + asset, new File(destFile, asset));
        }
    }

    private static void copyFile(@NonNull AssetManager assetManager, @NonNull String srcAsset, @NonNull File destFile)
        throws IOException {
        destFile.getParentFile().mkdirs();
        try (InputStream in = assetManager.open(srcAsset);
             FileOutputStream out = new FileOutputStream(destFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);
            // Otherwise a power cut right after the copy could leave the folder with its marker but empty files.
            out.getFD().sync();
        }
    }

    // Deletes a file, or a folder and everything in it, if they exist. Symbolic links are deleted, not followed.
    static void deleteRecursively(@NonNull File file) throws IOException {
        Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                if (e instanceof NoSuchFileException)
                    return FileVisitResult.CONTINUE;
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null)
                    throw e;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteOldMarkers(@NonNull File filesDir) {
        File[] oldMarkers = filesDir.listFiles((dir, name) -> OLD_MARKER_NAME.matcher(name).matches());
        if (oldMarkers == null)
            return;
        for (File file : oldMarkers)
            file.delete();
    }
}
