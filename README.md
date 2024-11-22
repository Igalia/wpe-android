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
<org.wpewebkit.wpeview.WPEView
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

For example, device debug build dependencies can be generated using
```bash
./tools/scripts/bootstrap.py --build --debug --arch=arm64
```
### Android Project
Once the bootstrap process is done and all the dependencies are cross-compiled and installed,
you should be able to generate android project from gradle files.
```bash
./gradlew assembleDebug
```
This will generate APKs in directory `tools/webdriver/build/outputs/apk/debug`

To install APK in device or emulator,
```bash
adb install ./tools/minibrowser/build/outputs/apk/debug/minibrowser-debug.apk
```

## Web Inspector

To enable Web Inspector access, you need to enable the remote inspector server and developer extras.

#### **1. Forward the Remote Inspector Port**

Before launching the application, ensure that the attached device or emulator is started. In the terminal, issue the following command:

```bash
adb forward tcp:5000 tcp:5000
```

#### **2. Enable Remote Inspector and Developer Extras**

Before creating any `WPEView` instance, enable the remote inspector server and developer extras:

```kotlin
WPEView.enableRemoteInspector(5000, true)
val view = WPEView().apply {
    settings.developerExtrasEnabled = true
    loadUrl("https://www.wpewebkit.org")
}
```

#### **3. Access the Web Inspector**

After completing the above steps, you can access the Web Inspector by opening the following URL in any browser:

[http://127.0.0.1:5000](http://127.0.0.1:5000)

## WebDriver

Following demostrates how to run a simple webdriver script on emulator

### 1. Create python virtual environment for Selenium

Create directory for Selenium (to any location you want)

```bash
python3 -m venv venv
source venv/bin/activate
pip install selenium
```

### 2. Create python Selenium script

Save following as simple_test.py

```bash
from selenium import webdriver

options = webdriver.WPEWebKitOptions()
# Custom browser
options.binary_location = "/"
# Extra browser arguments
options.add_argument("--automation")
# Remove incompatible capabilities keys
del(options._caps["platform"])
del(options._caps["version"])

driver = webdriver.Remote(command_executor="http://127.0.0.1:8888", options=options)
driver.get('http://www.wpewebkit.org')
driver.quit()
```
### 3. Run webdriver application on emulator

From android studio run webdriver application on x86-64 emulator.
After emulator has started issue following on terminal

```bash
adb forward tcp:8888 tcp:8888
```
### 4. Run WebDriver Selenium tests

From Selenium directory created previously run

```bash
python3 ./simple_test.py
```

## Declaring dependencies

To add a dependency on WPEView, you must add the Maven Central repository to your project. Read [Maven Central repository](https://central.sonatype.org/consume/consume-gradle/) for more information.

Add the dependencies for the artifacts you need in the `build.gradle` file for your app or module:

```groovy
dependencies {
    implementation "org.wpewebkit.wpeview:wpeview:0.1.2"
}
```

For more information about dependencies, see [Add build dependencies](https://developer.android.com/studio/build/dependencies).


## Known issues and limitations
* The universal wpewebkit bootstrap package is not yet supported.
* The scripts and build have only been tested in Linux.
