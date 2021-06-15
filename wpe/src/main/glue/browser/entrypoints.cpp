#include <dlfcn.h>
#include <memory>
#include <stdlib.h>

#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>

#include "browser.h"
#include "logging.h"
#include "looperthread.h"
#include "pageeventobserver.h"

extern "C" {
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_setupEnvironment(JNIEnv*, jobject, jstring);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_initLooperHelper(JNIEnv*, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_init(JNIEnv*, jobject, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_initLooperHelper(JNIEnv*, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_deinit(JNIEnv*, jobject);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_newPage(JNIEnv*, jobject, jobject, jint, jint, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_closePage(JNIEnv*, jobject, jint);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_loadURL(JNIEnv*, jobject, jint, jstring);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_goBack(JNIEnv*, jobject, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_goForward(JNIEnv*, jobject, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_stopLoading(JNIEnv*, jclass, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_reload(JNIEnv*, jobject, jint);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_surfaceCreated(JNIEnv*, jobject, jint, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_surfaceChanged(JNIEnv*, jobject, jint, jint, jint, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_surfaceRedrawNeeded(JNIEnv*, jobject, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_surfaceDestroyed(JNIEnv*, jobject, jint);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_touchEvent(JNIEnv*, jobject, jint, jlong, jint, jfloat, jfloat);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_setZoomLevel(JNIEnv*, jclass, jint, jdouble);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_setInputMethodContent(JNIEnv*, jclass, jint, jchar);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_BrowserGlue_deleteInputMethodContent(JNIEnv*, jclass, jint, jint);

std::unique_ptr<Browser> Browser::m_instance = nullptr;

// These are used by WebKit to call into the Java layer.
JNIEXPORT JNIEnv* s_BrowserGlue_env = 0;
JNIEXPORT jobject s_BrowserGlue_object = 0;

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_setupEnvironment(JNIEnv *env, jobject, jstring gioPath)
{
    ALOGV("BrowserGlue::setupEnvironment()");

    const char* _gioPath = env->GetStringUTFChars(gioPath, 0);
    setenv("GIO_EXTRA_MODULES", _gioPath, 1);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_init(JNIEnv* env, jobject, jobject glueObj)
{
    ALOGV("BrowserGlue.init()");
    s_BrowserGlue_env = env;
    s_BrowserGlue_object = glueObj;
    Browser::getInstance().init();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_initLooperHelper(JNIEnv* env, jobject)
{
    ALOGV("BrowserGlue.initLooperHelper()");
    LooperThread::initialize();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_deinit(JNIEnv*, jobject)
{
    ALOGV("BrowserGlue.deinit()");
    Browser::getInstance().deinit();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_newPage(JNIEnv *env, jobject, jobject pageObj, jint pageId, jint width, jint height)
{
    ALOGV("BrowserGlue.newWebView tid %d", gettid());
    jclass pageClass = env->GetObjectClass(pageObj);
    jclass _pageClass = reinterpret_cast<jclass>(env->NewGlobalRef(pageClass));
    jobject _pageObj = reinterpret_cast<jobject>(env->NewGlobalRef(pageObj));
    JavaVM *vm;
    env->GetJavaVM(&vm);

    Browser::getInstance().newPage(
            pageId, width, height, std::make_shared<PageEventObserver>(vm, _pageClass, _pageObj));

    jmethodID onReady = env->GetMethodID(pageClass, "onPageGlueReady", "()V");
    if (onReady == nullptr) {
        return;
    }
    env->CallVoidMethod(pageObj, onReady);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_closePage(JNIEnv*, jobject, jint pageId) {
    ALOGV("BrowserGlue.closePage %d", pageId);
    Browser::getInstance().closePage(pageId);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_loadURL(JNIEnv* env, jobject, jint pageId, jstring url)
{
    const char* urlChars = env->GetStringUTFChars(url, 0);
    jsize urlLength = env->GetStringUTFLength(url);
    Browser::getInstance().loadUrl(pageId, urlChars, urlLength);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_goBack(JNIEnv* env, jobject, jint pageId)
{
    Browser::getInstance().goBack(pageId);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_goForward(JNIEnv* env, jobject, jint pageId)
{
    Browser::getInstance().goForward(pageId);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_stopLoading(JNIEnv*, jclass, jint pageId)
{
    Browser::getInstance().stopLoading(pageId);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_reload(JNIEnv* env, jobject, jint pageId)
{
    Browser::getInstance().reload(pageId);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_surfaceCreated(JNIEnv* env, jobject, jint pageId, jobject jsurface)
{
    Browser::getInstance().surfaceCreated(pageId, ANativeWindow_fromSurface(env, jsurface));
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_surfaceChanged(JNIEnv*, jobject, jint pageId, jint format, jint width, jint height)
{
    Browser::getInstance().surfaceChanged(pageId, format, width, height);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_surfaceRedrawNeeded(JNIEnv*, jobject, jint pageId)
{
    Browser::getInstance().surfaceRedrawNeeded(pageId);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_surfaceDestroyed(JNIEnv*, jobject, jint pageId)
{
    Browser::getInstance().surfaceDestroyed(pageId);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_touchEvent(JNIEnv*, jobject, jint pageId, jlong time, jint type, jfloat x, jfloat y)
{
    Browser::getInstance().onTouch(pageId, time, type, x, y);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_setZoomLevel(JNIEnv*, jclass, jint pageId, jdouble zoomLevel)
{
    Browser::getInstance().setZoomLevel(pageId, zoomLevel);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_setInputMethodContent(JNIEnv *env, jclass clazz, jint pageId, jchar c) {
    Browser::getInstance().setInputMethodContent(pageId, c);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_BrowserGlue_deleteInputMethodContent(JNIEnv *env, jclass clazz, jint pageId, jint offset) {
    Browser::getInstance().deleteInputMethodContent(pageId, offset);
}
}