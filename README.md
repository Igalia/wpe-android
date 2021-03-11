# WPE Android

![logo](./logo.png)

[WPE WebKit](https://wpewebkit.org/) port for Android.

## WPEView API

WPEView wraps the WPE WebKit browser engine in a reusable Android library.
WPEView serves a similar purpose to Android's built-in WebView and tries to mimick
its API aiming to be an easy to use drop-in replacement with extended functionality.

Setting up WPEView in your Android application is fairly simple.

(TODO: package, distribute and document installation)

First, add the `WPEView` widget to your
[Activity layout](https://developer.android.com/training/basics/firstapp/building-ui):

```xml
<com.wpe.wpe.WPEView
        android:id="@+id/wpe_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        tools:context=".MainActivity"/>
```

And next, wire it in your Activity implementation to start using the API, for example, to load an URL:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    var browser = findViewById(R.id.wpe_view)
    browser?.loadUrl(INITIAL_URL)
}
```

To see WPEView in action check the [examples](examples) folder.

## Setting up your environment

### python3

The bootstrap script requires [python3](https://www.python.org/downloads/).

### Cross compile dependencies

WPE Android depends on a considerable amount of libraries, 
including [libWPE](https://github.com/WebPlatformForEmbedded/libwpe) and 
[WPEWebKit](https://github.com/WebPlatformForEmbedded/WPEWebKit). 
To ease the cross-compilation process we use 
[Cerbero](https://gitlab.freedesktop.org/gstreamer/cerbero). To set all things up run:

```bash
python3 ./scripts/bootstrap.py <arch>
```

where `arch` is the target architecture that you want to cross-compile to. 
Currently the only supported architecture is `arm64`.

This command will fetch `Cerbero`, the Android NDK and a bunch of dependencies required 
to cross-compile WPE Android dependencies. The process takes a significant amount of time.

You can optionally create a debug build of WPEWebKit adding `debug` to the bootstrap command:

```bash
python3 ./scripts/bootstrap.py <arch> debug
```

### Android Studio
[Android Studio](https://developer.android.com/studio/) is required to build and run WPE Android.
Once the bootstrap process is done and all the dependencies are cross-compiled and installed, 
you should be able to open the `launcher` demo with Android Studio and run it on a real device.

## Patching WPEWebKit for debugging purposes
The fastest way to patch WPEWebKit is to add your modifications directly to the
WPEWebKit's source cloned by Cerbero in the `build/build/sources/android_<arch>/wpewebkit-<version>`
folder. Once you are done adding your changes, execute the following command from the root of this repo:
```bash
python3 ./scripts/patch.py <arch> wpewebkit
```

## Known issues and limitations
* The only supported architecture at the moment is `arm64`.
* WPE Android does not work with an Android emulator due to EGL emulation issues.
* The scripts and build have only been tested in Linux.
