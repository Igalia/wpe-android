#include <algorithm>
#include <WPE/WebKit.h>
#include <wpe/input.h>
#include <wpe/view-backend.h>
#include <wpe-android/view-backend-exportable.h>
#include <glib.h>
#include <jni.h>

#include "logging.h"

static GMutex initMutex;
static GCond initCond;

static GThread* wpeThread;
static GMainContext* wpeThreadContext = NULL;
static GMainLoop* wpeThreadLoop;
static wpe_android_view_backend_exportable* s_viewBackendExportable;
static wpe_view_backend* wpeViewBackend;

static gboolean sourceCallback(gpointer)
{
    ALOGV("wpeThreadEntry() -- boop");
    return TRUE;
}

static gpointer wpeThreadEntry(gpointer, int width, int height)
{
    g_mutex_lock(&initMutex);
    ALOGV("wpeThreadEntry() -- entered, g_main_context_default() %p", g_main_context_default());

    // wpeThreadContext = g_main_context_new();
    wpeThreadLoop = g_main_loop_new(wpeThreadContext, FALSE);
    ALOGV("wpeThreadEntry() -- context %p loop %p", wpeThreadContext, wpeThreadLoop);

    g_cond_signal(&initCond);
    g_mutex_unlock(&initMutex);

    if (0) {
        GSource* source = g_timeout_source_new(10000);
        g_source_set_callback(source, sourceCallback, NULL, NULL);
        g_source_attach(source, wpeThreadContext);
        g_source_unref(source);
    }

    g_main_context_push_thread_default(wpeThreadContext);

    ALOGV("wpeThreadEntry() -- operating on WK API");
    WKContextRef context = WKContextCreate();

    WKPageConfigurationRef pageConfiguration = WKPageConfigurationCreate();
    WKPageConfigurationSetContext(pageConfiguration, context);

    WKStringRef pageGroupIdentifier = WKStringCreateWithUTF8CString("WPEPageGroup");
    WKPageGroupRef pageGroup = WKPageGroupCreateWithIdentifier(pageGroupIdentifier);
    WKPageConfigurationSetPageGroup(pageConfiguration, pageGroup);
    WKRelease(pageGroup);
    WKRelease(pageGroupIdentifier);

    ALOGV("wpeThreadEntry() -- creating a new view");
    struct wpe_view_backend* viewBackend;
    {
        s_viewBackendExportable = wpe_android_view_backend_exportable_create(std::max(0, width), std::max(0, height));
        viewBackend = wpe_android_view_backend_exportable_get_view_backend(s_viewBackendExportable);
    }
    WKViewRef view = WKViewCreateWithViewBackend(viewBackend, pageConfiguration);
    WKRelease(pageConfiguration);

    WKURLRef url = WKURLCreateWithUTF8CString("https://www.wapo.st");
    // WKURLRef url = WKURLCreateWithUTF8CString("https://www.igalia.com");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://www.politico.com");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://helloracer.com/webgl/");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://people.igalia.com/zdobersek/poster-circle/index.html");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://motherfuckingwebsite.com/");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://www.google.com");
    // WKURLRef url = WKURLCreateWithUTF8CString("about:blank");
    WKPageLoadURL(WKViewGetPage(view), url);
    WKRelease(url);

    ALOGV("wpeThreadEntry() -- running via GMainLoop %p for GMainContext %p", wpeThreadLoop, wpeThreadContext);
    g_main_loop_run(wpeThreadLoop);
    ALOGV("wpeThreadEntry() -- quitting");

    WKRelease(view);

    WKRelease(context);

    g_main_context_pop_thread_default(wpeThreadContext);

    g_main_loop_unref(wpeThreadLoop);
    wpeThreadLoop = NULL;

    return NULL;
}

void wpe_uiprocess_glue_init(JNIEnv* env, jobject glueObj, jint width, jint height)
{
    ALOGV("wpe_instance_init() (%d,%d)", width, height);

    if (!wpeThreadContext)
        wpeThreadContext = g_main_context_new();

#if 0
    g_mutex_lock(&initMutex);
    wpeThread = g_thread_new("WPE", wpeThreadEntry, NULL);
    g_cond_wait(&initCond, &initMutex);
    g_mutex_unlock(&initMutex);
#endif
    wpeThreadEntry(NULL, width, height);

    ALOGV("wpe_instance_init() -- done");
}

void wpe_uiprocess_glue_deinit()
{
    ALOGV("wpe_instance_deinit()");

    g_main_loop_quit(wpeThreadLoop);

#if 0
    g_thread_join(wpeThread);
    wpeThread = NULL;
#endif

    ALOGV("wpe_instance_deinit() -- done");
}

static gboolean frameCompleteCallback(gpointer)
{
    wpe_android_view_backend_exportable_dispatch_frame_complete(s_viewBackendExportable);
    return FALSE;
}

void wpe_uiprocess_glue_frame_complete()
{
    GSource* source = g_idle_source_new();
    g_source_set_callback(source, frameCompleteCallback, NULL, NULL);
    g_source_set_priority(source, G_PRIORITY_HIGH + 30);
    g_source_attach(source, wpeThreadContext);
}

static gboolean touchEventCallback(gpointer data)
{
    struct wpe_input_touch_event_raw* touchEventRaw = reinterpret_cast<struct wpe_input_touch_event_raw*>(data);

    struct wpe_input_touch_event touchEvent;
    touchEvent.touchpoints = touchEventRaw;
    touchEvent.touchpoints_length = 1;
    touchEvent.type = touchEventRaw->type;
    touchEvent.id = 0;
    touchEvent.time = touchEventRaw->time;

    struct wpe_view_backend* viewBackend = wpe_android_view_backend_exportable_get_view_backend(s_viewBackendExportable);
    wpe_view_backend_dispatch_touch_event(viewBackend, &touchEvent);

    return FALSE;
}

void wpe_uiprocess_glue_touch_event(jlong time, jint type, jfloat x, jfloat y)
{
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

    struct wpe_input_touch_event_raw* touchEventRaw = g_new0(struct wpe_input_touch_event_raw, 1);
    touchEventRaw->type = touchEventType;
    touchEventRaw->time = (uint32_t)time;
    touchEventRaw->id = 0;
    touchEventRaw->x = (int32_t)x;
    touchEventRaw->y = (int32_t)y;

    GSource* source = g_idle_source_new();
    g_source_set_callback(source, touchEventCallback, touchEventRaw, g_free);
    g_source_set_priority(source, G_PRIORITY_HIGH + 30);
    g_source_attach(source, wpeThreadContext);
}