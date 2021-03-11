#include <algorithm>
#include <wpe/webkit.h>
#include <wpe/wpe.h>
#include <wpe-android/view-backend-exportable.h>
#include <glib.h>
#include <glib-object.h>
#include <gobject/gvaluecollector.h>

#include <jni.h>

#include "logging.h"

static GMutex initMutex;
static GCond initCond;

static GMainContext *pageThreadContext = NULL;
static GMainLoop *pageThreadLoop;
static wpe_android_view_backend_exportable *s_viewBackendExportable;
static WebKitWebView *view = NULL;
static char *s_url = NULL;

static void load_changed(WebKitWebView* view, WebKitLoadEvent loadEvent, gpointer) {
    if (loadEvent == WEBKIT_LOAD_STARTED) {
        ALOGV("WEBKIT_LOAD_STARTED");
    }
    if (loadEvent == WEBKIT_LOAD_STARTED) {
        ALOGV("WEBKIT_LOAD_REDIRECTED");
    }
    if (loadEvent == WEBKIT_LOAD_STARTED) {
        ALOGV("WEBKIT_LOAD_COMMITED");
    }
    if (loadEvent == WEBKIT_LOAD_STARTED) {
        ALOGV("WEBKIT_LOAD_FINISHED");
    }
}

static gboolean load_url_callback(gpointer data) {
    ALOGV("load_url in %p %s", view, data);

    g_signal_connect(view, "load-changed", G_CALLBACK(load_changed), NULL);

    char *url = reinterpret_cast<char *>(data);
    webkit_web_view_load_uri(view, url);

    return FALSE;
}

void load_url(char *url) {
    GSource *source = g_idle_source_new();
    g_source_set_callback(source, load_url_callback, url, NULL);
    g_source_set_priority(source, G_PRIORITY_HIGH + 30);
    g_source_attach(source, pageThreadContext);

    g_free(url);
}

static void page_thread_entry(int width, int height) {
    pipe_stdout_to_logcat();
    enable_gst_debug();

    g_mutex_lock(&initMutex);
    ALOGV("page_thread_entry() -- entered, g_main_context_default() %p",
          g_main_context_default());

    pageThreadLoop = g_main_loop_new(pageThreadContext, FALSE);

    g_cond_signal(&initCond);
    g_mutex_unlock(&initMutex);

    g_main_context_push_thread_default(pageThreadContext);

    webkit_web_context_new();

    ALOGV("Creating a new WebkitWebView");
    struct wpe_view_backend *wpeBackend;
    {
        s_viewBackendExportable = wpe_android_view_backend_exportable_create(std::max(0, width),
                                                                             std::max(0, height));
        wpeBackend = wpe_android_view_backend_exportable_get_view_backend(s_viewBackendExportable);
    }

    auto *viewBackend = webkit_web_view_backend_new(wpeBackend, NULL, NULL);

    view = webkit_web_view_new(viewBackend);

    if (s_url != NULL) {
        load_url(s_url);
    }

    ALOGV("page_thread_entry() -- running via GMainLoop %p for GMainContext %p",
          pageThreadLoop, pageThreadContext);
    g_main_loop_run(pageThreadLoop);
    ALOGV("page_thread_entry() -- quitting");

    g_main_context_pop_thread_default(pageThreadContext);

    g_main_loop_unref(pageThreadLoop);
    pageThreadLoop = NULL;
}

void wpe_page_glue_init(jint width, jint height) {
    ALOGV("wpe_page_glue_init() (%d, %d)", width, height);

    if (!pageThreadContext) {
        pageThreadContext = g_main_context_new();
    }

    page_thread_entry(width, height);
}

void wpe_page_glue_deinit() {
    ALOGV("wpe_page_glue_deinit()");

    g_main_loop_quit(pageThreadLoop);

    ALOGV("wpe_page_glue_deinit() -- done");
}

void wpe_page_glue_set_page_url(const char *urlData, jsize urlSize) {
    char *url = g_strndup(urlData, urlSize);
    if (pageThreadContext == NULL) {
        ALOGV("wpe_page_glue_set_page_url queue url to load");
        s_url = url;
        return;
    }
    ALOGV("wpe_page_glue_set_page_url -- URL %s thread %p", url, pageThreadContext);
    load_url(url);
}

static gboolean frame_complete_callback(gpointer) {
    wpe_android_view_backend_exportable_dispatch_frame_complete(s_viewBackendExportable);
    return FALSE;
}

void wpe_page_glue_frame_complete() {
    GSource *source = g_idle_source_new();
    g_source_set_callback(source, frame_complete_callback, NULL, NULL);
    g_source_set_priority(source, G_PRIORITY_HIGH + 30);
    g_source_attach(source, pageThreadContext);
}

static gboolean touch_event_callback(gpointer data) {
    struct wpe_input_touch_event_raw *touchEventRaw = reinterpret_cast<struct wpe_input_touch_event_raw *>(data);

    struct wpe_input_touch_event touchEvent;
    touchEvent.touchpoints = touchEventRaw;
    touchEvent.touchpoints_length = 1;
    touchEvent.type = touchEventRaw->type;
    touchEvent.id = 0;
    touchEvent.time = touchEventRaw->time;

    struct wpe_view_backend *viewBackend = wpe_android_view_backend_exportable_get_view_backend(
            s_viewBackendExportable);
    wpe_view_backend_dispatch_touch_event(viewBackend, &touchEvent);

    return FALSE;
}

void wpe_page_glue_touch_event(jlong time, jint type, jfloat x, jfloat y) {
    wpe_input_touch_event_type touchEventType = wpe_input_touch_event_type_null;
    switch (type) {
        case 0:
            touchEventType = wpe_input_touch_event_type_down;
            break;
        case 1:
            touchEventType = wpe_input_touch_event_type_motion;
            break;
        case 2:
            touchEventType = wpe_input_touch_event_type_up;
            break;
    }

    struct wpe_input_touch_event_raw *touchEventRaw = g_new0(struct wpe_input_touch_event_raw, 1);
    touchEventRaw->type = touchEventType;
    touchEventRaw->time = (uint32_t) time;
    touchEventRaw->id = 0;
    touchEventRaw->x = (int32_t) x;
    touchEventRaw->y = (int32_t) y;

    GSource *source = g_idle_source_new();
    g_source_set_callback(source, touch_event_callback, touchEventRaw, g_free);
    g_source_set_priority(source, G_PRIORITY_HIGH + 30);
    g_source_attach(source, pageThreadContext);
}
