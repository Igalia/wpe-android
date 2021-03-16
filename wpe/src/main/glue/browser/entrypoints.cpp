#include <jni.h>

#include "logging.h"
#include "browser.h"
#include <dlfcn.h>
#include <stdlib.h>

extern "C" {
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_init(JNIEnv*, jobject, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_deinit(JNIEnv*, jobject);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_newWebView(JNIEnv*, jobject, jobject, jint, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_destroyWebView(JNIEnv*, jobject, jlong);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_loadURL(JNIEnv*, jobject, jlong, jstring);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_frameComplete(JNIEnv*, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_touchEvent(JNIEnv*, jobject, jlong, jint, jfloat, jfloat);
}

// These are used by WebKit to call into the Java layer.
JNIEXPORT JNIEnv* s_WPEUIProcessGlue_env = 0;
JNIEXPORT jobject s_WPEUIProcessGlue_object = 0;

// This is used for cross-thread callbacks.
jclass s_PageClass = nullptr;
jobject s_PageObj = nullptr;

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_init(JNIEnv* env, jobject, jobject glueObj)
{
    ALOGV("PageGlue.init()");
    s_WPEUIProcessGlue_env = env;
    s_WPEUIProcessGlue_object = glueObj;
    wpe_browser_glue_init();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_deinit(JNIEnv*, jobject)
{
    ALOGV("PageGlue.deinit()");
    wpe_browser_glue_deinit();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_newWebView(JNIEnv* env, jobject, jobject pageObj, jint width, jint height)
{
    ALOGV("BrowserGlue.newWebView tid %d", gettid());
    jclass localPageClass = env->GetObjectClass(pageObj);
    s_PageClass = reinterpret_cast<jclass>(env->NewGlobalRef(localPageClass));
    s_PageObj = reinterpret_cast<jobject>(env->NewGlobalRef(pageObj));
    wpe_browser_glue_new_web_view(width, height, [env, pageObj] (long viewRef) {
        jmethodID onReady = env->GetMethodID(s_PageClass, "onReady", "(J)V");
        if (onReady == nullptr) {
            return;
        }
        ALOGV("webview %ld", (jlong)viewRef);
        env->CallObjectMethod(s_PageObj, onReady, (jlong)viewRef);
        env->DeleteGlobalRef(s_PageClass);
        env->DeleteGlobalRef(s_PageObj);
    });
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_loadURL(JNIEnv* env, jobject, jlong webView, jstring url)
{
    const char* urlChars = env->GetStringUTFChars(url, 0);
    jsize urlLength = env->GetStringUTFLength(url);
    wpe_browser_glue_load_url(webView, urlChars, urlLength);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_frameComplete(JNIEnv*, jobject)
{
    wpe_browser_glue_frame_complete();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_touchEvent(JNIEnv*, jobject, jlong time, jint type, jfloat x, jfloat y)
{
    wpe_browser_glue_touch_event(time, type, x, y);
}
