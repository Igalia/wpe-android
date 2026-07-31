# Tips & tricks

## Building a patched version of WPEWebKit for debugging purposes

The fastest way of building WPEWebKit with modifications is to add your modifications
directly to the WPEWebKit source code cloned by Cerbero in
`[project root]/build/cerbero/build/sources/android_<arch>/wpewebkit-<version>`
folder.

Once your modifications are ready, execute the following command from the root of this repo:

```bash
./tools/scripts/build-patch.py --arch <arch>
```

You can use the same command with any recipe, just add `--recipe <recipe name>` at the
end of the command line.

## Making sense of logcat stack traces

When a native crash occurs running WPE Android the adb logcat prints something like:

```
03-12 12:20:39.345  F  [30472/30472] DEBUG    *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
03-12 12:20:39.345  F  [30472/30472] DEBUG    Build fingerprint: 'google/redfin/redfin:11/RQ2A.210305.006/7119741:user/release-keys'
03-12 12:20:39.346  F  [30472/30472] DEBUG    pid: 30164, tid: 30465, name: Thread-5  >>> org.wpewebkit.tools.minibrowser <<<
03-12 12:20:39.346  F  [30472/30472] DEBUG    signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------
03-12 12:20:39.515  F  [30472/30472] DEBUG    backtrace:
03-12 12:20:39.515  F  [30472/30472] DEBUG          #00 pc 000000000004e178  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168) (BuildId: bca874ad82277777df5c95ca3b0f6e6f)
03-12 12:20:39.515  F  [30472/30472] DEBUG          #01 pc 0000000000601a24  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-2.0.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #02 pc 000000000088fd1c  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-2.0.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #03 pc 0000000000043220  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEAndroidRuntime.so
```

This is not specially helpful, as it shows shared library addresses instead of the usual `<source-file>:<line-number`.
To turn this output into something more readable you need to make use of the `ndk-stack` tool. Luckily we have all that
we need with Cerbero. Run this command from the WPE Android root path:

```ssh
adb logcat | build/cerbero/build/android-ndk-27/ndk-stack -sym wpeview/src/main/cpp/imported/lib/arm64-v8a
```

You should see something like:

```
********** Crash dump: **********
Build fingerprint: 'google/redfin/redfin:11/RQ2A.210305.006/7119741:user/release-keys'
#00 0x000000000004e178 /apex/com.android.runtime/lib64/bionic/libc.so (abort+168) (BuildId: bca874ad82277777df5c95ca3b0f6e6f)
#01 0x0000000000601a24 /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-2.0.so
WTFCrashWithInfo(int, char const*, char const*, int)
/home/user/dev/wpe-android/build/cerbero/build/sources/android_arm64/wpewebkit-git/_builddir/DerivedSources/ForwardingHeaders/wtf/Assertions.h:673:5
#02 0x000000000088fd1c /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-2.0.so
WebKit::WebProcessProxy::WebProcessProxy(WebKit::WebProcessPool&, WebKit::WebsiteDataStore*, WebKit::WebProcessProxy::IsPrewarmed)
/home/user/dev/wpe-android/build/cerbero/build/sources/android_arm64/wpewebkit-git/_builddir/../Source/WebKit/UIProcess/WebProcessProxy.cpp:205:5

[...]
```

## Add color to adb logcat

[logcat-colorize](https://github.com/carlonluca/logcat-colorize) is a helpful tool to add some color to the adb logcat
output.

To get a nicer logcat with WPE Android you can run the following command:

```ssh
adb logcat -v time | egrep -i '(wpe|WPE|webkit|WebKit|WEBKIT)' | logcat-colorize
```

## Calling Java method from the JNI layer

To find the internal name of Java classes run this command from the root path:

```ssh
javap -p -s wpeview/build/intermediates/javac/debug/classes/org/wpewebkit/wpe/<.class file>
```

replacing `<.class file>` with the name of the `.class` file containing the method you want to call. For example:

```ssh
javap -p -s wpeview/build/intermediates/javac/debug/classes/org/wpewebkit/wpe/WebKitWebView.class
```

This gives an output like:

```
Compiled from "WebKitWebView.java"
public final class org.wpewebkit.wpe.WebKitWebView {
  private long mNativePtr;
    descriptor: J
  public org.wpewebkit.wpe.WebKitWebView(org.wpewebkit.wpe.WPEDisplay, org.wpewebkit.wpe.WebKitWebContext, org.wpewebkit.wpe.WPEToplevel, org.wpewebkit.wpe.WebKitNetworkSession, org.wpewebkit.wpe.WebKitSettings);
    descriptor: (Lorg/wpewebkit/wpe/WPEDisplay;Lorg/wpewebkit/wpe/WebKitWebContext;Lorg/wpewebkit/wpe/WPEToplevel;Lorg/wpewebkit/wpe/WebKitNetworkSession;Lorg/wpewebkit/wpe/WebKitSettings;)V
  private native long nativeInit(long, long, long, long, long);
    descriptor: (JJJJJ)J
  private native void nativeDestroy(long);
    descriptor: (J)V
  private native void nativeLoadUrl(long, java.lang.String);
    descriptor: (JLjava/lang/String;)V
  private native void nativeEvaluateJavascript(long, java.lang.String, org.wpewebkit.wpe.WebKitWebView$EvalCallbackHolder);
    descriptor: (JLjava/lang/String;Lorg/wpewebkit/wpe/WebKitWebView$EvalCallbackHolder;)V
}
```

The exact method set depends on the class you inspect. The useful parts are the
JNI class path and each `descriptor` string. Current low-level proxy classes live
in `org.wpewebkit.wpe`, with matching native bridge files under
`wpeview/src/main/cpp/capi/`. Older examples that refer to `BrowserGlue`,
`Page`, or `org.wpewebkit.wpe.gfx.View` describe the pre-refactor API path.

## Debugging Java and native code from WPEWebProcess and/or WPENetworkProcess

The procedure is for the [Android Studio](https://developer.android.com/studio) official IDE.

1- Uncomment the `android.os.Debug.waitForDebugger();` instruction in the `loadNativeLibraries()` method of the
corresponding service Java code. That is to say:

- wpeview/src/main/java/org/wpewebkit/wpe/services/WebProcessService, or
- wpeview/src/main/java/org/wpewebkit/wpe/services/NetworkProcessService

This instruction will wait for the Android debugger when the service native code is loaded at the moment the
corresponding process is attached to the JVM.

2- Force the dual debugger (Java + Native) in Run/Debug configuration (the automatic detection won't work). You can do
so by clicking on the combo-box on the upper menu bar, showing the name of the executed activity (like
`tools.minibrowser` for example). When selected, this combo-box shows an `Edit configurations...` entry which opens a
window where you can configure the `Debugger`. Select `Dual (Java + Native)`, as the main application process doesn't
use native code, the IDE cannot auto-detect the dual debugger.

3- Install and launch the main application on an emulator or a real device (you can do all at once by clicking on the
*Play* icon on the upper menu bar, or by hitting *Ctrl+F5*).

4- Click on `Attach Debugger to Android Process` from the upper menu bar. It will open a small tool window with the list
of running processes on the Android emulator or real device. Then select the process you want to debug when it appears
in this list.

## Configure GStreamer debugging logs and pipelines graphs dumping

This only works with debuggable builds of wpe-android. This feature is disabled in release builds.

You need to launch the application at least once to create the application persistent folder on your real device or
emulator. This folder will be accessible in `Android/data/[your application id]/files` when activating files transfer
through USB. Then, just create a property file called `gstreamer.props` in this folder.

Configurable properties are:

- *debugLevels*: configure GStreamer debugging levels, same as the value passed to GST_DEBUG. Default value if omitted
  is "*:FIXME".
- *dumpDotDir*: configure the folder to which GStreamer will dump the pipelines graphs when gst_debug_bin_to_dot_file()
  is called. Configured folder will be created under `Android/data/[your application id]/files` root directory. Default
  value if omitted is "" (no dumps).
- *noColor*: set to "true" to disable GStreamer logs coloring (can remove noise with logcat when not using
  *logcat-colorize*). Default value if omitted is "false" (logs will use colors).

Example of `gstreamer.props` file:

```
debugLevels = *:INFO,decodebin:LOG
dumpDotDir = gst/dot
noColor = true
```
