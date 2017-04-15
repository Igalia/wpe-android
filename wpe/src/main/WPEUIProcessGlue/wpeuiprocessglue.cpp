#include <algorithm>
#include <WPE/WebKit.h>
#include <wpe/view-backend.h>
#include <wpe-android/view-backend-exportable.h>
#include <glib.h>
#include <jni.h>

#include "logging.h"

static GMutex initMutex;
static GCond initCond;

static GThread* wpeThread;
static GMainContext* wpeThreadContext;
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

    wpeThreadContext = g_main_context_new();
    wpeThreadLoop = g_main_loop_new(wpeThreadContext, FALSE);
    ALOGV("wpeThreadEntry() -- context %p loop %p", wpeThreadContext, wpeThreadLoop);

    g_cond_signal(&initCond);
    g_mutex_unlock(&initMutex);

    if (1) {
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

    // WKURLRef url = WKURLCreateWithUTF8CString("https://www.wapo.st");
    // WKURLRef url = WKURLCreateWithUTF8CString("https://www.igalia.com");
    WKURLRef url = WKURLCreateWithUTF8CString("http://www.politico.com");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://helloracer.com/webgl/");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://people.igalia.com/zdobersek/poster-circle/index.html");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://motherfuckingwebsite.com/");
    // WKURLRef url = WKURLCreateWithUTF8CString("http://www.google.com");
    // WKURLRef url = WKURLCreateWithUTF8CString("about:blank");
    WKPageLoadURL(WKViewGetPage(view), url);
    WKRelease(url);

    ALOGV("wpeThreadEntry() -- running");
    g_main_loop_run(wpeThreadLoop);
    ALOGV("wpeThreadEntry() -- quitting");

    WKRelease(view);
    WKRelease(context);

    g_main_context_pop_thread_default(wpeThreadContext);

    g_main_loop_unref(wpeThreadLoop);
    g_main_context_unref(wpeThreadContext);
    wpeThreadLoop = NULL;
    wpeThreadContext = NULL;

    return NULL;
}

void wpe_uiprocess_glue_init(JNIEnv* env, jobject glueObj, jint width, jint height)
{
    ALOGV("wpe_instance_init() (%d,%d)", width, height);

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

void wpe_uiprocess_glue_frame_complete()
{
    wpe_android_view_backend_exportable_dispatch_frame_complete(s_viewBackendExportable);
}