# WPE Android

[![LGPLv2.1 License](https://img.shields.io/badge/License-LGPLv2.1-blue.svg?style=flat)](/LICENSE.md)
[![CI build](https://github.com/Igalia/wpe-android/actions/workflows/build.yml/badge.svg)](https://github.com/Igalia/wpe-android/actions/workflows/build.yml)

![logo](/logo.png)

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
<com.wpe.wpeview.WPEView
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

    val browser = findViewById(R.id.wpe_view)
    browser?.loadUrl(INITIAL_URL)
}
```

To see WPEView in action check the [tools](tools) folder.

## Setting up your environment

### python3

The bootstrap script requires [python3](https://www.python.org/downloads/).

### Getting the dependencies

WPE Android depends on a considerable amount of libraries,
including [libWPE](https://github.com/WebPlatformForEmbedded/libwpe) and
[WPEWebKit](https://github.com/WebPlatformForEmbedded/WPEWebKit).
To ease the cross-compilation process we use
[Cerbero](https://gitlab.freedesktop.org/gstreamer/cerbero). To set all things up run:

```bash
./tools/scripts/bootstrap.py
```

This command will fetch the required binaries and place them in the expected location.

If you want to build (and/or modify) the dependencies you can pass the `--build` option:

```bash
./tools/scripts/bootstrap.py --build
```

This command will fetch `Cerbero`, the Android NDK and a bunch of dependencies required
to cross-compile WPE Android dependencies. The process takes a significant amount of time.

You can optionally create a debug build of WPEWebKit passing the `--debug` option to the bootstrap command:

```bash
./tools/scripts/bootstrap.py --build --debug
```

Finally, the bootstrap option accepts the `--arch` option to set the target architecture.
Currently supported architectures are `arm64` and `x86_64`.


### Android Studio
[Android Studio](https://developer.android.com/studio/) is required to build and run WPE Android.
Once the bootstrap process is done and all the dependencies are cross-compiled and installed,
you should be able to open the `launcher` demo with Android Studio and run it on a real device.

## Known issues and limitations
* The universal wpewebkit bootstrap package is not yet supported.
* The scripts and build have only been tested in Linux.
