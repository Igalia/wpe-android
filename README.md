# WPE Android

[WPE WebKit](https://wpewebkit.org/) port for Android.

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
python3 ./bootstrap.py <arch>
```

where `arch` is the target architecture that you want to cross-compile to. 
Currently the only supported architecture is `arm64`.

This command will fetch `Cerbero`, the Android NDK and a bunch of dependencies required 
to cross-compile WPE Android dependencies. The process takes a significant amount of time.

### Android Studio
[Android Studio](https://developer.android.com/studio/) is required to build and run WPE Android.
Once the bootstrap process is done and all the dependencies are cross-compiled and installed, 
you should be able to open the `launcher` demo with Android Studio and run it on a real device.

## Known issues and limitations
* The only supported architecture at the moment is `arm64`.
* WPE Android does not work with an Android emulator due to EGL emulation issues.
