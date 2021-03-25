#include <dlfcn.h>
#include <memory>
#include <stdlib.h>

#include <jni.h>

#include "browser.h"
#include "logging.h"
#include "pageeventobserver.h"

extern "C" {
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_init(JNIEnv*, jobject, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_deinit(JNIEnv*, jobject);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_newWebView(JNIEnv*, jobject, jobject, jint, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_destroyWebView(JNIEnv*, jobject, jlong);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_loadURL(JNIEnv*, jobject, jlong, jstring);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_goBack(JNIEnv*, jobject, jlong);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_goForward(JNIEnv*, jobject, jlong);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_reload(JNIEnv*, jobject, jlong);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_frameComplete(JNIEnv*, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_touchEvent(JNIEnv*, jobject, jlong, jint, jfloat, jfloat);
}

// These are used by WebKit to call into the Java layer.
JNIEXPORT JNIEnv* s_BrowserGlue_env = 0;
JNIEXPORT jobject s_BrowserGlue_object = 0;

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_init(JNIEnv* env, jobject, jobject glueObj)
{
    ALOGV("BrowserGlue.init()");
    s_BrowserGlue_env = env;
    s_BrowserGlue_object = glueObj;
    wpe_browser_glue_init();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_deinit(JNIEnv*, jobject)
{
    ALOGV("BrowserGlue.deinit()");
    wpe_browser_glue_deinit();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_newWebView(JNIEnv* env, jobject, jobject pageObj, jint width, jint height)
{
    ALOGV("BrowserGlue.newWebView tid %d", gettid());
    jclass pageClass = env->GetObjectClass(pageObj);
    jclass _pageClass = reinterpret_cast<jclass>(env->NewGlobalRef(pageClass));
    jobject _pageObj = reinterpret_cast<jobject>(env->NewGlobalRef(pageObj));
    JavaVM *vm;
    env->GetJavaVM(&vm);

    std::unique_ptr<PageEventObserver> observer = std::make_unique<PageEventObserver>(vm, _pageClass, _pageObj);

    wpe_browser_glue_new_web_view(width, height, std::move(observer), [env, pageObj, pageClass] (long viewRef) {
        jmethodID onReady = env->GetMethodID(pageClass, "onWebViewReady", "(J)V");
        if (onReady == nullptr) {
            return;
        }
        ALOGV("webview %ld", (jlong)viewRef);
        env->CallVoidMethod(pageObj, onReady, (jlong)viewRef);
    });
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_destroyWebView(JNIEnv*, jobject, jlong webView) {
    ALOGV("BrowserGlue.destroyWebView tid %d", gettid());
    wpe_browser_glue_close_web_view(webView);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_loadURL(JNIEnv* env, jobject, jlong webView, jstring url)
{
    const char* urlChars = env->GetStringUTFChars(url, 0);
    jsize urlLength = env->GetStringUTFLength(url);
    wpe_browser_glue_load_url(webView, urlChars, urlLength);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_goBack(JNIEnv* env, jobject, jlong webView)
{
    wpe_browser_glue_go_back(webView);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_goForward(JNIEnv* env, jobject, jlong webView)
{
    wpe_browser_glue_go_forward(webView);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_reload(JNIEnv* env, jobject, jlong webView)
{
    wpe_browser_glue_reload(webView);
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