#include <algorithm>
#include <wpe/webkit.h>
#include <wpe/wpe.h>
#include <wpe-android/view-backend-exportable.h>
#include <glib.h>
#include <jni.h>

#include "logging.h"

static GMutex initMutex;
static GCond initCond;

static GMainContext *sessionThreadContext = NULL;
static GMainLoop *sessionThreadLoop;
static wpe_android_view_backend_exportable *s_viewBackendExportable;
static WebKitWebView *view = NULL;
static char *s_url = NULL;

static gboolean load_url_callback(gpointer data) {
    ALOGV("load_url in %p", view);

    char *url = reinterpret_cast<char *>(data);
    webkit_web_view_load_uri(view, url);

    return FALSE;
}

void load_url(char *url) {
    GSource *source = g_idle_source_new();
    g_source_set_callback(source, load_url_callback, url, NULL);
    g_source_set_priority(source, G_PRIORITY_HIGH + 30);
    g_source_attach(source, sessionThreadContext);

    g_free(url);
}

static void session_thread_entry(int width, int height) {
    pipe_stdout_to_logcat();
    enable_gst_debug();

    g_mutex_lock(&initMutex);
    ALOGV("session_thread_entry() -- entered, g_main_context_default() %p",
          g_main_context_default());

    sessionThreadLoop = g_main_loop_new(sessionThreadContext, FALSE);
    ALOGV("session_thread_entry() -- context %p loop %p", sessionThreadContext, sessionThreadLoop);

    g_cond_signal(&initCond);
    g_mutex_unlock(&initMutex);

    g_main_context_push_thread_default(sessionThreadContext);

    ALOGV("session_thread_entry() -- operating on WK API");
    webkit_web_context_new();

    ALOGV("session_thread_entry() -- creating a new view");
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

    ALOGV("session_thread_entry() -- running via GMainLoop %p for GMainContext %p",
          sessionThreadLoop, sessionThreadContext);
    g_main_loop_run(sessionThreadLoop);
    ALOGV("session_thread_entry() -- quitting");

    g_main_context_pop_thread_default(sessionThreadContext);

    g_main_loop_unref(sessionThreadLoop);
    sessionThreadLoop = NULL;
}

void wpe_session_glue_init(jint width, jint height) {
    ALOGV("wpe_instance_init() (%d, %d)", width, height);

    if (!sessionThreadContext) {
        sessionThreadContext = g_main_context_new();
    }

    session_thread_entry(width, height);

    ALOGV("wpe_instance_init() -- done");
}

void wpe_session_glue_deinit() {
    ALOGV("wpe_instance_deinit()");

    g_main_loop_quit(sessionThreadLoop);

    ALOGV("wpe_instance_deinit() -- done");
}

void wpe_session_glue_set_page_url(const char *urlData, jsize urlSize) {
    char *url = g_strndup(urlData, urlSize);
    if (sessionThreadContext == NULL) {
        ALOGV("wpe_session_glue_set_page_url queue url to load");
        s_url = url;
        return;
    }
    ALOGV("wpe_session_glue_set_page_url -- URL %s thread %p", url, sessionThreadContext);
    load_url(url);
}

static gboolean frame_complete_callback(gpointer) {
    wpe_android_view_backend_exportable_dispatch_frame_complete(s_viewBackendExportable);
    return FALSE;
}

void wpe_session_glue_frame_complete() {
    GSource *source = g_idle_source_new();
    g_source_set_callback(source, frame_complete_callback, NULL, NULL);
    g_source_set_priority(source, G_PRIORITY_HIGH + 30);
    g_source_attach(source, sessionThreadContext);
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

void wpe_session_glue_touch_event(jlong time, jint type, jfloat x, jfloat y) {
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
    g_source_attach(source, sessionThreadContext);
}
