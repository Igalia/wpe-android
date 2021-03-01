# Architecture

## High level design

![high_level_design.png](./images/high_level_design.png)

## WPE

TODO

## Boot process

### Web Activity creation

Everything start with the creation of
[WPEActivity](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEActivity.java#L89)
which represents the WebKit UI process. After construction, its [onCreate](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEActivity.java#L89) method is called.

WPEActivity creates an instance of [WebProcess.Glue](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/upgrade/wpe/src/main/java/com/wpe/wpe/WebProcess/Glue.java)
and an instance of [WPEView](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEView.java).

It sets the page URL through Glue. The URL is taken from the [AndroidManifest](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/launcher/src/main/AndroidManifest.xml#L18).

It spawns the [WPEUIProcessThread](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEActivity.java#L26)
thread and passes the Glue and WPEView instances. This thread waits until the Glue instance is passed.

Inside WPEUIProcessThread `WPEView.ensureSurfaceTexture` and `Glue.init` are called.

[onResume](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEActivity.java#L126)
is eventually called after `onCreate`. Within this callback `WPEView.onResume` is called and a first call to
[requestRender](https://developer.android.com/reference/android/opengl/GLSurfaceView?hl=en#requestRender()) is made.

### WPEView creation

WPEView implements [GLSurfaceView](https://developer.android.com/reference/android/opengl/GLSurfaceView?hl=en).
Check this [intro](https://source.android.com/devices/graphics/arch-sv-glsv).

On creation it sets [Renderer](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEView.java#L29),
which is an implementation of [GLSurfaceView.Renderer](https://developer.android.com/reference/android/opengl/GLSurfaceView?hl=en#setRenderer(android.opengl.GLSurfaceView.Renderer)),
to initialize GLSurfaceView. And sets the renderer mode to [RENDERMODE_WHEN_DIRTY](https://developer.android.com/reference/android/opengl/GLSurfaceView?hl=en#RENDERMODE_WHEN_DIRTY),
so frames are rendered on demand, when `requestRender` is called.

Right after creation, [ensureSurfaceTexture()](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEView.java#L224)
is called by WPEActivity, which makes the view wait until the surface is created. Surface creation happens on rendering thread creation. When the surface is available
[GLSurfaceView.Renderer.surfaceCreated](https://developer.android.com/reference/android/opengl/GLSurfaceView?hl=en#surfaceCreated(android.view.SurfaceHolder)) is called.

Communication between the view and the rendering thread happens through
[GLSurfaceView.queueEvent](https://developer.android.com/reference/android/opengl/GLSurfaceView?hl=en#setRenderer(android.opengl.GLSurfaceView.Renderer)).


### UIProcess Glue initialization
A new [GLib Main Context](https://developer.gnome.org/programming-guidelines/unstable/main-contexts.html.en)
and a new [GLib Main Loop](https://developer.gnome.org/programming-guidelines/unstable/main-contexts.html.en)
are created for handling touch events.

This is where we start using the [WPEWebKit API](https://wpewebkit.org/reference/wpewebkit/2.23.90/index.html)
and libWPE:

- Creation of a new [WebKitWebContext](https://wpewebkit.org/reference/wpewebkit/2.23.90/WebKitWebContext.html).
- Creation of [wpe_android_view_backend_exportable](https://gitlab.igalia.com/ferjm/wpebackend-android/-/blob/f647c97cf7e1319d2042093fe68e102b4cae2cf8/src/view-backend-exportable.cpp#L73)
and get [wpe_view_backend] from it.
- Creation of a new [WebKitWebViewBackend](https://wpewebkit.org/reference/wpewebkit/2.23.90/WebKitWebViewBackend.html).
- Creation of a new [WebkitWebView](https://wpewebkit.org/reference/wpewebkit/2.23.90/WebKitWebView.html).
- Load URL on the view.
- Run GMainLoop.

Loading the URL makes WebKit spawn the Network and Web processes:
- [WebPageProxy::loadRequest](https://github.com/WebKit/WebKit/blob/d5b70228584db6b8f219f7467a8b0c3d07d88ae8/Source/WebKit/UIProcess/WebPageProxy.cpp#L1304)
- [WebPageProxy::launchProcess](https://github.com/WebKit/WebKit/blob/d5b70228584db6b8f219f7467a8b0c3d07d88ae8/Source/WebKit/UIProcess/WebPageProxy.cpp#L827)
- [ProcessLauncher::launchProcess](https://github.com/WebKit/WebKit/blob/d5b70228584db6b8f219f7467a8b0c3d07d88ae8/Source/WebKit/UIProcess/Launcher/glib/ProcessLauncherGLib.cpp#L102)

This is [patched](https://gitlab.igalia.com/ferjm/cerbero/-/blob/b9c3b76efb1ed7e2fedfcd6838e638a194df2da8/recipes/wpewebkit/0001-Android-spawn-services.patch)
to spawn Android services equivalent to the Web and Network processes. This is done by making WebKit call
[WPEUIProcessGlue::launchProcess](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/UIProcess/Glue.java#L34).
The reason for this is to workaround the fact that the forking syscall is forbidden in non-rooted Android.

### Launch Web and Network services/processes
The services are spawned from [WPEActivity::launchService](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEActivity.java#L146)
via an [Intent](https://developer.android.com/guide/components/intents-filters).
Along with the Intent a [WPEServiceConnection](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/UIProcess/WPEServiceConnection.java)
instance implementing [android.content.ServiceConnection](https://developer.android.com/reference/android/content/ServiceConnection)
is created.

On Service creation Android calls the [WPEService.onCreate](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEService.java#L80)
callback. Within this method the
[WPEServiceProcessThread](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEService.java#L38)
thread is spawned. The thread waits until the Service file descriptors are provided. Once that happens
[initializeService()](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEService.java#L122)
(implemented by the [web](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WebProcess/Service.java#L57)
and the [network](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/NetworkProcess/Service.java#L32)
processes) is called.

When the WPEService is bound to the WPEActivity, its [WPEServiceConnection.onServiceConnected](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEService.java#L80)
is triggered. Within this callback we provide the Service's file descriptors (used for IPC communication with the WPE internals(?)) through the
[IWPEService.connect](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/WPEService.java#L18)
method, unblocking the WPEServiceProcessThread.
This creates a [WPEServiceConnection](https://gitlab.igalia.com/ferjm/wpe-android/-/blob/0fdbb911791155c3772a078586b698bd2c7a0309/wpe/src/main/java/com/wpe/wpe/UIProcess/WPEServiceConnection.java)
that implements [android.content.ServiceConnection](https://developer.android.com/reference/android/content/ServiceConnection)
