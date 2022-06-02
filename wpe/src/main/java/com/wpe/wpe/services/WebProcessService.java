package com.wpe.wpe.services;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.wpe.wpe.ProcessType;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class WebProcessService extends WPEService
{
    private static final String LOGTAG = "WPEWebProcess";

    // Bump this version number if you make any changes to the font config
    // or the gstreamer plugins or else they won't be applied.
    private static final String assetsVersion = "web_process_assets_v1";

    @Override
    protected void setupServiceEnvironment()
    {
        Context context = getApplicationContext();
        if (ServiceUtils.needAssets(context, assetsVersion)) {
            ServiceUtils.copyFileOrDir(context, getAssets(), "gstreamer-1.0");
            ServiceUtils.copyFileOrDir(context, getAssets(), "fontconfig/fonts.conf");
            ServiceUtils.saveAssetsVersion(context, assetsVersion);
        }

        List<String> envStrings = new ArrayList<>(44);
        envStrings.add("FONTCONFIG_PATH");
        envStrings.add(new File(context.getFilesDir(), "fontconfig").getAbsolutePath());

        String gstreamerPluginPath = new File(context.getFilesDir(), "gstreamer-1.0").getAbsolutePath();
        envStrings.add("GST_PLUGIN_PATH");
        envStrings.add(gstreamerPluginPath);
        envStrings.add("GST_PLUGIN_SYSTEM_PATH");
        envStrings.add(gstreamerPluginPath);

        envStrings.add("GIO_EXTRA_MODULES");
        envStrings.add(new File(context.getFilesDir(), "gio").getAbsolutePath());

        ApplicationInfo appInfo = context.getApplicationInfo();
        envStrings.add("LD_LIBRARY_PATH");
        envStrings.add(appInfo.nativeLibraryDir);
        envStrings.add("LIBRARY_PATH");
        envStrings.add(appInfo.nativeLibraryDir);

        String cachePath = context.getCacheDir().getAbsolutePath();
        envStrings.add("TMP");
        envStrings.add(cachePath);
        envStrings.add("TEMP");
        envStrings.add(cachePath);
        envStrings.add("TMPDIR");
        envStrings.add(cachePath);
        envStrings.add("XDG_CACHE_HOME");
        envStrings.add(cachePath);
        envStrings.add("XDG_RUNTIME_DIR");
        envStrings.add(cachePath);

        envStrings.add("GST_REGISTRY");
        envStrings.add(new File(context.getCacheDir(), "registry.bin").getAbsolutePath());
        envStrings.add("GST_REGISTRY_UPDATE");
        envStrings.add("no");
        envStrings.add("GST_REGISTRY_REUSE_PLUGIN_SCANNER");
        envStrings.add("no");

        String filesPath = context.getFilesDir().getAbsolutePath();
        envStrings.add("HOME");
        envStrings.add(filesPath);
        envStrings.add("XDG_DATA_DIRS");
        envStrings.add(filesPath);
        envStrings.add("XDG_CONFIG_DIRS");
        envStrings.add(filesPath);
        envStrings.add("XDG_CONFIG_HOME");
        envStrings.add(filesPath);
        envStrings.add("XDG_DATA_HOME");
        envStrings.add(filesPath);

        if ((appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) == ApplicationInfo.FLAG_DEBUGGABLE) {
            String gstDebugLevels = "*:FIXME";

            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                File externalFilesDir = context.getExternalFilesDir(null);
                if (externalFilesDir != null && (externalFilesDir.exists() || externalFilesDir.mkdirs())) {

                    File gstreamerProps = new File(externalFilesDir, "gstreamer.props");
                    if (gstreamerProps.exists()) {

                        Properties props = new Properties();
                        try (FileInputStream in = new FileInputStream(gstreamerProps)) {
                            props.load(in);
                        } catch (Exception e) {
                            Log.w(LOGTAG, "Cannot read gstreamer.props file", e);
                        }

                        gstDebugLevels = props.getProperty("debugLevels", gstDebugLevels);

                        String noColor = props.getProperty("noColor");
                        if (noColor != null && noColor.compareToIgnoreCase("true") == 0) {
                            envStrings.add("GST_DEBUG_NO_COLOR");
                            envStrings.add("1");
                        }

                        String dumpDotDirFolder = props.getProperty("dumpDotDir");
                        if (dumpDotDirFolder != null && !dumpDotDirFolder.isEmpty()) {
                            File dumpDotDir = new File(externalFilesDir, dumpDotDirFolder);
                            if (dumpDotDir.exists() || dumpDotDir.mkdirs()) {
                                envStrings.add("GST_DEBUG_DUMP_DOT_DIR");
                                envStrings.add(dumpDotDir.getAbsolutePath());
                            }
                        }
                    }
                }
            }

            envStrings.add("GST_DEBUG");
            envStrings.add(gstDebugLevels);
        }

        WebProcessGlue.setupEnvironment(envStrings.toArray(new String[envStrings.size()]));
    }

    @Override
    protected void initializeServiceMain(@NonNull ParcelFileDescriptor parcelFd)
    {
        Log.v(LOGTAG, "initializeServiceMain() fd: " + parcelFd + ", native value: " + parcelFd.getFd());
        WebProcessGlue.initializeMain(ProcessType.WebProcess.getValue(), parcelFd.detachFd());
    }
}
