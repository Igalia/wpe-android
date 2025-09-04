package org.wpewebkit.wpe.services;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.WKProcessType;
import org.wpewebkit.wpe.WKVersions;

import java.io.File;

public class WebDriverProcessService extends WPEService {

    private static final String LOGTAG = "WPEWebDriverProcess";

    @Override
    protected void loadNativeLibraries() {
        // To debug the sub-process with Android Studio (Java and native code), you must:
        // 1- Uncomment the following instruction to wait for the debugger before loading native code.
        // 2- Force the dual debugger (Java + Native) in Run/Debug configuration (the automatic detection won't work).
        // 3- Launch the application (:tools:webdriver).
        // 4- Click on "Attach Debugger to Android Process" and select this service process from the list.

        // android.os.Debug.waitForDebugger();

        System.loadLibrary("WPEWebDriver");
        System.loadLibrary("WPEAndroidService");
    }
    @Override
    protected void setupServiceEnvironment() {
        final String assetsVersion = WKVersions.versionedAssets("webdriver_process");
        Context context = getApplicationContext();
        if (ServiceUtils.needAssets(context, assetsVersion)) {
            ServiceUtils.copyFileOrDir(context, getAssets(), "gio", true);
            ServiceUtils.saveAssetsVersion(context, assetsVersion);
        }

        String[] envStringsArray = {"XDG_RUNTIME_DIR", context.getCacheDir().getAbsolutePath(), "GIO_EXTRA_MODULES",
                                    new File(context.getFilesDir(), "gio").getAbsolutePath()};

        setupNativeEnvironment(envStringsArray);
    }
    @Override
    protected void initializeServiceMain(long pid, @NonNull ParcelFileDescriptor parcelFd) {
        Log.d(LOGTAG, "initializeServiceMain()");

        initializeNativeMain(pid, WKProcessType.WebDriverProcess.getValue(), 0);
    }
}
