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
end of the commane line.

## Making sense of logcat stack traces

When a native crash occurs running WPE Android the adb logcat prints something like:

```
03-12 12:20:39.315  I  [30472/30472] crash_dump64 obtaining output fd from tombstoned, type: kDebuggerdTombstone
03-12 12:20:39.337  I  [904/904] tombstoned received crash request for pid 30465
03-12 12:20:39.339  I  [30472/30472] crash_dump64 performing dump of process 30164 (target tid = 30465)
03-12 12:20:39.345  F  [30472/30472] DEBUG    *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
03-12 12:20:39.345  F  [30472/30472] DEBUG    Build fingerprint: 'google/redfin/redfin:11/RQ2A.210305.006/7119741:user/release-keys'
03-12 12:20:39.345  F  [30472/30472] DEBUG    Revision: 'MP1.0'
03-12 12:20:39.345  F  [30472/30472] DEBUG    ABI: 'arm64'
03-12 12:20:39.346  F  [30472/30472] DEBUG    Timestamp: 2021-03-12 12:20:39+0100
03-12 12:20:39.346  F  [30472/30472] DEBUG    pid: 30164, tid: 30465, name: Thread-5  >>> org.wpewebkit.tools.minibrowser <<<
03-12 12:20:39.346  F  [30472/30472] DEBUG    uid: 10393
03-12 12:20:39.346  F  [30472/30472] DEBUG    signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------
03-12 12:20:39.346  F  [30472/30472] DEBUG        x0  0000000000000000  x1  0000000000007701  x2  0000000000000006  x3  0000007260758900
03-12 12:20:39.346  F  [30472/30472] DEBUG        x4  0000000000000000  x5  0000000000000000  x6  0000000000000000  x7  0000000000000000
03-12 12:20:39.346  F  [30472/30472] DEBUG        x8  00000000000000f0  x9  0000007502cfb7c0  x10 ffffff80fffffbdf  x11 0000000000000001
03-12 12:20:39.346  F  [30472/30472] DEBUG        x12 0000000000000000  x13 47c3000000020102  x14 000000000000008f  x15 00000000000008f0
03-12 12:20:39.346  F  [30472/30472] DEBUG        x16 0000007502d93c80  x17 0000007502d75320  x18 00000071fbeb2000  x19 00000000000075d4
03-12 12:20:39.346  F  [30472/30472] DEBUG        x20 0000000000007701  x21 00000000ffffffff  x22 00000071b24ee5d8  x23 00000071b24ee3a8
03-12 12:20:39.346  F  [30472/30472] DEBUG        x24 00000071b24ee4a8  x25 000000726075a000  x26 0000000000000002  x27 0000007260758dd8
03-12 12:20:39.346  F  [30472/30472] DEBUG        x28 0000007260758df8  x29 0000007260758980
03-12 12:20:39.346  F  [30472/30472] DEBUG        lr  0000007502d29148  sp  00000072607588e0  pc  0000007502d29178  pst 0000000000001000
03-12 12:20:39.498  D  [4505/4505] GRIL-S   [8730]> UPDATE_REGULATORY_DOMAIN
03-12 12:20:39.496  W  [1001/1001] radioext@1.0-se type=1400 audit(0.0:32314): avc: denied { search } for name="radio" dev="dm-16" ino=212 scontext=u:r:hal_radioext_default:s0 tcontext=u:object_r:vendor_radio_data_file:s0 tclass=dir permissive=0
03-12 12:20:39.499  D  [4505/5555] GRIL-S   [8730]< UPDATE_REGULATORY_DOMAIN
03-12 12:20:39.515  F  [30472/30472] DEBUG    backtrace:
03-12 12:20:39.515  F  [30472/30472] DEBUG          #00 pc 000000000004e178  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168) (BuildId: bca874ad82277777df5c95ca3b0f6e6f)
03-12 12:20:39.515  F  [30472/30472] DEBUG          #01 pc 0000000000601a24  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #02 pc 000000000088fd1c  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #03 pc 000000000088c804  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #04 pc 00000000008fdff4  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #05 pc 00000000008eccfc  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #06 pc 00000000008e81cc  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #07 pc 00000000008f354c  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #08 pc 000000000001a580  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libgobject-2.0.so
03-12 12:20:39.515  F  [30472/30472] DEBUG          #09 pc 000000000001a2c8  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libgobject-2.0.so (g_object_new_valist+760)
03-12 12:20:39.515  F  [30472/30472] DEBUG          #10 pc 0000000000019dd0  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libgobject-2.0.so (g_object_new+112)
03-12 12:20:39.515  F  [30472/30472] DEBUG          #11 pc 00000000008ff7e0  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so (webkit_web_view_new+64)
03-12 12:20:39.515  F  [30472/30472] DEBUG          #12 pc 0000000000043220  /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEPageGlue.so (BuildId: f7e820673ca7de23f522c1742eca350cbb41d82d)
```

This is not specially helpful, as it shows shared library addresses instead of the usual `<source-file>:<line-number`.
To turn this output into something more readable you need to make use of the `ndk-stack` tool. Luckily we have all that
we need with Cerbero. Run this command from the WPE Android root path:

```ssh
adb logcat | cerbero/build/android-ndk-23/ndk-stack -sym wpe/src/main/cpp/imported/lib/arm64-v8a
```

You should see something like:

```
********** Crash dump: **********
Build fingerprint: 'google/redfin/redfin:11/RQ2A.210305.006/7119741:user/release-keys'
WARNING: Mismatched build id for wpe/imported/lib/arm64-v8a/libc.so
WARNING:   Expected bca874ad82277777df5c95ca3b0f6e6f
WARNING:   Found    c790eec6e15870bab2cf223ed3cfa192
#00 0x000000000004e178 /apex/com.android.runtime/lib64/bionic/libc.so (abort+168) (BuildId: bca874ad82277777df5c95ca3b0f6e6f)
#01 0x0000000000601a24 /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
WTFCrashWithInfo(int, char const*, char const*, int)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/DerivedSources/ForwardingHeaders/wtf/Assertions.h:673:5
#02 0x000000000088fd1c /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
WebKit::WebProcessProxy::WebProcessProxy(WebKit::WebProcessPool&, WebKit::WebsiteDataStore*, WebKit::WebProcessProxy::IsPrewarmed)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/WebProcessProxy.cpp:205:5
#03 0x000000000088c804 /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
WebKit::WebProcessProxy::create(WebKit::WebProcessPool&, WebKit::WebsiteDataStore*, WebKit::WebProcessProxy::IsPrewarmed, WebKit::WebProcessProxy::ShouldLaunchProcess)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/WebProcessProxy.cpp:141:32
WebKit::WebProcessPool::createWebPage(WebKit::PageClient&, WTF::Ref<API::PageConfiguration, WTF::DumbPtrTraits<API::PageConfiguration> >&&)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/WebProcessPool.cpp:1320:0
#04 0x00000000008fdff4 /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
WKWPE::View::View(wpe_view_backend*, API::PageConfiguration const&)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/API/wpe/WPEView.cpp:74:25
#05 0x00000000008eccfc /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
WKWPE::View::create(wpe_view_backend*, API::PageConfiguration const&)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/API/wpe/WPEView.h:70:20
webkitWebViewCreatePage(_WebKitWebView*, WTF::Ref<API::PageConfiguration, WTF::DumbPtrTraits<API::PageConfiguration> >&&)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/API/glib/WebKitWebView.cpp:2276:0
#06 0x00000000008e81cc /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
webkitWebContextCreatePageForWebView(_WebKitWebContext*, _WebKitWebView*, _WebKitUserContentManager*, _WebKitWebView*, _WebKitWebsitePolicies*)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/API/glib/WebKitWebContext.cpp:1926:5
#07 0x00000000008f354c /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libWPEWebKit-1.0_3.so
webkitWebViewConstructed(_GObject*)
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/wpewebkit-2.30.4/_builddir/../Source/WebKit/UIProcess/API/glib/WebKitWebView.cpp:765:5
#08 0x000000000001a580 /data/app/~~WJLHau6kHswZ6spsTXvQUw==/org.wpewebkit.tools.minibrowser-VIWitBTgpOsWgxNfsbpj1Q==/lib/arm64/libgobject-2.0.so
g_object_new_internal
/home/ferjm/dev/igalia/wpe-android/cerbero/build/sources/android_arm64/glib-2.62.6/_builddir/../gobject/gobject.c:1867:5

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
javap -p -s wpe/build/intermediates/javac/debug/classes/org/wpewebkit/wpe/<.class file>
```

replacing `<.class file>` with the name of the `.class` file containing the method you want to call. For example:

```ssh
javap -p -s wpe/build/intermediates/javac/debug/classes/org/wpewebkit/wpe/Page.class
```

This gives an output like:

```
Compiled from "Page.java"
public class org.wpewebkit.wpe.Page {
  private final java.lang.String LOGTAG;
    descriptor: Ljava/lang/String;
  private final org.wpewebkit.wpe.BrowserGlue glue;
    descriptor: Lorg/wpewebkit/wpe/BrowserGlue;
  private final org.wpewebkit.wpe.gfx.WPESurfaceView view;
    descriptor: Lorg/wpewebkit/wpe/gfx/View;
  private final android.content.Context context;
    descriptor: Landroid/content/Context;
  private final java.util.ArrayList<org.wpewebkit.wpe.services.WPEServiceConnection> services;
    descriptor: Ljava/util/ArrayList;
  private long webViewRef;
    descriptor: J
  public org.wpewebkit.wpe.Page(android.content.Context, java.lang.String, org.wpewebkit.wpe.gfx.WPESurfaceView, org.wpewebkit.wpe.BrowserGlue);
    descriptor: (Landroid/content/Context;Ljava/lang/String;Lorg/wpewebkit/wpe/gfx/View;Lorg/wpewebkit/wpe/BrowserGlue;)V

  public void close();
    descriptor: ()V

  protected void finalize() throws java.lang.Throwable;
    descriptor: ()V

  public void onReady(long);
    descriptor: (J)V

  public void launchService(int, android.os.Parcelable[], java.lang.Class);
    descriptor: (I[Landroid/os/Parcelable;Ljava/lang/Class;)V

  public void dropService(org.wpewebkit.wpe.services.WPEServiceConnection);
    descriptor: (Lorg/wpewebkit/wpe/services/WPEServiceConnection;)V

  public org.wpewebkit.wpe.gfx.WPESurfaceView view();
    descriptor: ()Lorg/wpewebkit/wpe/gfx/View;

  public void loadUrl(java.lang.String);
    descriptor: (Ljava/lang/String;)V
```

where the different `descriptor` values contain the strings you are looking for.

## Debugging Java and native code from WPEWebProcess and/or WPENetworkProcess

The procedure is for the [Android Studio](https://developer.android.com/studio) official IDE.

1- Uncomment the `android.os.Debug.waitForDebugger();` instruction in the `loadNativeLibraries()` method of the
corresponding service Java code. That is to say:

- wpe/src/main/java/org/wpewebkit/wpe/services/WebProcessService, or
- wpe/src/main/java/org/wpewebkit/wpe/services/NetworkProcessService

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
