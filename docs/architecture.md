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
<org.wpewebkit.wpeview.WPEView
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
extension in charge of managing the Surface that is handed over to WebKit to do the actual rendering.

## WPE

TODO

## Boot up process

Everything starts with the instanciation of a new [WPEView](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpeview/WPEView.java#L28).
After construction its [onAttachedToWindow](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpeview/WPEView.java#L51)
method is called. This queues a task to create a new [Page](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpeview/WPEView.java#L60)
instance through the [Browser](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Browser.java#L28)
singleton instance. If this is the first time the Browser instance is obtained, [Browser construction](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Browser.java#L277)
happens, creating an instance of [BrowserGlue](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/BrowserGlue.java#L10)
and spawning [UIProcessThread](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Browser.java#L240).
This is the thread where the actual WebKit's UIProcess logic runs.
On execution it creates an instance of [WebKitWebContext](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/glue/browser/browser.cpp#L78)
and [runs the main loop](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/glue/browser/browser.cpp#L82).

On [Page construction](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Page.java#L30)
an instance of [gfx.View](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/gfx/View.java#L30)
is created and [PageThread](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Page.java#L82)
is spawned to request the creation of a [Surface Texture](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/gfx/View.java#L254).
In addition, an instance of [WebKitWebView](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/glue/browser/page.h#L41)
is created through [BrowserGlue](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Page.java#L185).
The `Page` instance [gets and keeps a reference](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/glue/browser/page.h#L41)
of the just created WebKitWebView.

Once we have a URL to load, a valid Surface and a valid `WebKitWebView` reference, we request
the URL load [through BrowserGlue](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Page.java#L245).

Loading an URL triggers the creation of the Web and Network processes. WebKit requests the creation of its auxiliary processes through the
[BrowserGlue.launchProcess](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/BrowserGlue.java#L56)
method.

The auxiliary process creation request is
[handled by the Browser](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Browser.java#L342)
which picks the active Page instance to assign the process identifier given by WebKit and to request the actual process launch.

The auxiliary processes are actually [Android Services](https://developer.android.com/guide/components/services)

Browser [keeps a registry](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Browser.java#L67)
with a match between process identifier, Page and ServiceConnection.

The active `Page` instance is the final responsible of
[spawning](https://github.com/Igalia/wpe-android/blob/ea9529dcf183d823226721d0edf41950890b6a8f/wpe/src/main/java/org/wpewebkit/wpe/Page.java#L208)
the auxiliary process / Android Service.

