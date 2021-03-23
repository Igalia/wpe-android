# Architecture

## Building blocks

![high_level_design.png](./images/high_level_design.png)

### WPEView
WPEView wraps WPE WebKit browser engine in a reusable Android library.
WPEView serves a similar purpose to Android's built-in WebView and tries to mimick its API aiming
to be an easy to use drop-in replacement with extended functionality.

WPEView is the entry point for WPE Android users. It exposes methods to load urls, navigate back
and forward, reload, etc.

WPEView inherits from [android.widget.FrameLayout](https://developer.android.com/reference/kotlin/android/widget/FrameLayout)
and it is meant to be added to Activity layouts this way:

```xml
<com.wpe.wpeview.WPEView
        android:id="@+id/wpe_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        tools:context=".MainActivity"/>
```

Every instance of WPEView is registered with the main `Browser` and it has a single `Page` associated.

### Browser
Top level Singleton object. Among other duties it:

- manages the creation and destruction of `Page` instances.
- funnels `WPEView` API calls to the appropriate `Page` instance.
- manages the Android Services equivalent to WebKit's auxiliary processes (Web and Network processes).
- hosts the UIProcess thread where the WebKitWebContext instance lives and the main loop is run.

### Page
A Page roughly corresponds to a tab in a regular browser UI.
There is a 1:1 relationship between WPEView and Page.
Each Page instance has its own `wpe.wpe.gfx.View` and `WebKitWebView` instances associated.
It also keeps references to the Services that host the logic of WebKit's auxiliary
processes (Web and Network processes).

### gfx.View
[android.opengl.GLSurfaceView](https://developer.android.com/reference/android/opengl/GLSurfaceView?hl=en)
extension in charge of managing the Surface that is handled over to WebKit to do the actual rendering.

## WPE

TODO

## Boot process

TODO
