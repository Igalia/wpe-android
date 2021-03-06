#include "Browser.h"
#include "Environment.h"
#include "JNIHelper.h"
#include "Logging.h"
#include "LooperThread.h"
#include "PageEventObserver.h"
#include "Service.h"

#include <android/native_window_jni.h>
#include <wpe/wpe.h>

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*);
}

namespace {
jweak s_browserGlue_object = nullptr;

void setupEnvironment(JNIEnv*, jclass, jobjectArray envStringsArray)
{
    ALOGV("BrowserGlue::setupEnvironment() [tid %d]", gettid());
    Wpe::Android::pipeStdoutToLogcat();
    Wpe::Android::configureEnvironment(envStringsArray);
}

void init(JNIEnv* env, jclass, jobject glueObj)
{
    ALOGV("BrowserGlue::init(%p) [tid %d]", glueObj, gettid());

    if (s_browserGlue_object == nullptr)
        s_browserGlue_object = env->NewWeakGlobalRef(glueObj);

    Browser::getInstance().init();
}

void initLooperHelper(JNIEnv*, jclass)
{
    ALOGV("BrowserGlue::initLooperHelper() [tid %d]", gettid());

    LooperThread::initialize();
}

void shut(JNIEnv* env, jclass)
{
    ALOGV("BrowserGlue::shut() [tid %d]", gettid());

    Browser::getInstance().shut();

    if (s_browserGlue_object != nullptr) {
        env->DeleteWeakGlobalRef(s_browserGlue_object);
        s_browserGlue_object = nullptr;
    }
}

void newPage(JNIEnv* env, jclass, jobject pageObj, jint pageId, jint width, jint height)
{
    ALOGV("BrowserGlue::newPage(%p, %d, %d, %d) [tid %d]", pageObj, pageId, width, height, gettid());

    jclass pageClass = env->GetObjectClass(pageObj);
    Browser::getInstance().newPage(pageId, width, height, std::make_shared<PageEventObserver>(env, pageClass, pageObj));

    jmethodID onReady = env->GetMethodID(pageClass, "onPageGlueReady", "()V");
    if (onReady != nullptr) {
        env->CallVoidMethod(pageObj, onReady);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
    env->DeleteLocalRef(pageClass);
}

void closePage(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::closePage(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().closePage(pageId);
}

void loadURL(JNIEnv* env, jclass, jint pageId, jstring url)
{
    const char* urlChars = env->GetStringUTFChars(url, nullptr);
    ALOGV("BrowserGlue::loadURL(%d, %s) [tid %d]", pageId, urlChars, gettid());
    jsize urlLength = env->GetStringUTFLength(url);
    Browser::getInstance().loadUrl(pageId, urlChars, urlLength);
    env->ReleaseStringUTFChars(url, urlChars);
}

void goBack(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::goBack(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().goBack(pageId);
}

void goForward(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::goForward(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().goForward(pageId);
}

void stopLoading(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::stopLoading(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().stopLoading(pageId);
}

void reload(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::reload(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().reload(pageId);
}

void surfaceCreated(JNIEnv* env, jclass, jint pageId, jobject jSurface)
{
    ALOGV("BrowserGlue::surfaceCreated(%d, %p) [tid %d]", pageId, jSurface, gettid());
    Browser::getInstance().surfaceCreated(pageId, ANativeWindow_fromSurface(env, jSurface));
}

void surfaceChanged(JNIEnv*, jclass, jint pageId, jint format, jint width, jint height)
{
    ALOGV("BrowserGlue::surfaceChanged(%d, %d, %d, %d) [tid %d]", pageId, format, width, height, gettid());
    Browser::getInstance().surfaceChanged(pageId, format, width, height);
}

void surfaceRedrawNeeded(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::surfaceRedrawNeeded(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().surfaceRedrawNeeded(pageId);
}

void surfaceDestroyed(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::surfaceDestroyed(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().surfaceDestroyed(pageId);
}

void touchEvent(JNIEnv*, jclass, jint pageId, jlong time, jint type, jfloat x, jfloat y)
{
    ALOGV(
        "BrowserGlue::touchEvent(%d, %ld, %d, %f, %f) [tid %d]", pageId, static_cast<long>(time), type, x, y, gettid());
    Browser::getInstance().onTouch(pageId, time, type, x, y);
}

void setZoomLevel(JNIEnv*, jclass, jint pageId, jdouble zoomLevel)
{
    ALOGV("BrowserGlue::setZoomLevel(%d, %f) [tid %d]", pageId, zoomLevel, gettid());
    Browser::getInstance().setZoomLevel(pageId, zoomLevel);
}

void setInputMethodContent(JNIEnv*, jclass, jint pageId, jchar c)
{
    ALOGV("BrowserGlue::setInputMethodContent(%d, %c) [tid %d]", pageId, c, gettid());
    Browser::getInstance().setInputMethodContent(pageId, c);
}

void deleteInputMethodContent(JNIEnv*, jclass, jint pageId, jint offset)
{
    ALOGV("BrowserGlue::deleteInputMethodContent(%d, %d) [tid %d]", pageId, offset, gettid());
    Browser::getInstance().deleteInputMethodContent(pageId, offset);
}

void requestExitFullscreenMode(JNIEnv*, jclass, jint pageId)
{
    ALOGV("BrowserGlue::requestExitFullscreen(%d) [tid %d]", pageId, gettid());
    Browser::getInstance().requestExitFullscreen(pageId);
}

void updateAllPageSettings(JNIEnv* env, jclass, jint pageId, jobject jPageSettings)
{
    ALOGV("BrowserGlue::updateAllPageSettings(%d) [tid %d]", pageId, gettid());

    PageSettings settings;

    jclass jPageSettingsClass = env->GetObjectClass(jPageSettings);

    jmethodID methodID = env->GetMethodID(jPageSettingsClass, "getUserAgentString", "()Ljava/lang/String;");
    if (methodID != nullptr) {
        jstring jniString = reinterpret_cast<jstring>(env->CallObjectMethod(jPageSettings, methodID));
        const char* str = env->GetStringUTFChars(jniString, nullptr);
        settings.setUserAgent(str);
        env->ReleaseStringUTFChars(jniString, str);
    } else
        ALOGE("Cannot update user agent setting (cannot find \"getUserAgentString\" method)");

    methodID = env->GetMethodID(jPageSettingsClass, "getMediaPlaybackRequiresUserGesture", "()Z");
    if (methodID != nullptr) {
        jboolean require = env->CallBooleanMethod(jPageSettings, methodID);
        settings.setMediaPlayerRequiresUserGesture(require);
    } else
        ALOGE("Cannot update media playback requires user gesture setting (cannot find \"getUserAgentString\" method)");

    env->DeleteLocalRef(jPageSettingsClass);

    Browser::getInstance().updateAllPageSettings(pageId, settings);
}

// Process management API

struct AndroidProcessProvider {
    struct wpe_process_provider* wpe_provider;
};

void* createProcessProvider(struct wpe_process_provider* process_provider)
{
    ALOGV("BrowserGlue createProcessProvider()");
    return new AndroidProcessProvider {process_provider};
}

void destroyProcessProvider(void* data)
{
    ALOGV("BrowserGlue destroyProcessProvider()");
    delete reinterpret_cast<AndroidProcessProvider*>(data);
}

int32_t launchProcess(void* data, enum wpe_process_type wpeProcessType, void* options)
{
    ALOGV("BrowserGlue launchProcess()");
    auto* provider = reinterpret_cast<AndroidProcessProvider*>(data);
    if (!provider)
        return -1;

    auto** argv = reinterpret_cast<char**>(options);
    if (!argv)
        return -1;

    uint64_t pid = std::strtoll(argv[0], nullptr, 10);
    int32_t fd = std::stoi(argv[1]);

    static auto processTypeToAndroidProcessType = [](enum wpe_process_type wpeProcessType) {
        switch (wpeProcessType) {
        case WPE_PROCESS_TYPE_WEB:
            return Wpe::Android::ProcessType::WebProcess;

        case WPE_PROCESS_TYPE_NETWORK:
            return Wpe::Android::ProcessType::NetworkProcess;

        case WPE_PROCESS_TYPE_GPU:
        case WPE_PROCESS_TYPE_WEB_AUTHN:
        default:
            return Wpe::Android::ProcessType::TypesCount;
        }
    };

    auto processType = processTypeToAndroidProcessType(wpeProcessType);
    if (processType < Wpe::Android::ProcessType::FirstType || processType >= Wpe::Android::ProcessType::TypesCount) {
        ALOGE("Cannot launch process (invalid process type: %d)", static_cast<int>(processType));
        return -1;
    }

    ALOGV("BrowserGlue launchProcess - pid: %lu, processType: %d, fd: %d)", static_cast<unsigned long>(pid),
        static_cast<int>(processType), fd);

    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        if (!env->IsSameObject(s_browserGlue_object, nullptr)) {
            jobject obj = env->NewLocalRef(s_browserGlue_object);
            jclass klass = env->GetObjectClass(obj);

            jmethodID methodID = env->GetMethodID(klass, "launchProcess", "(JII)V");
            if (methodID != nullptr) {
                env->CallVoidMethod(obj, methodID, static_cast<jlong>(pid), static_cast<jint>(processType), fd);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    ALOGE("Cannot launch process (exception occurred on Java side)");
                }
            } else
                ALOGE("Cannot launch process (cannot find \"launchProcess\" method)");

            env->DeleteLocalRef(klass);
            env->DeleteLocalRef(obj);
        } else
            ALOGE("Cannot launch process (BrowserGlue has been garbage collected)");
    } catch (...) {
        ALOGE("Cannot launch process (JNI environment error)");
    }

    return 0;
}

void terminateProcess(void* data, int32_t pid)
{
    ALOGV("BrowserGlue terminateProcess()");
    auto* provider = reinterpret_cast<AndroidProcessProvider*>(data);
    if (!provider)
        return;

    uint64_t pid64 = pid;
    ALOGV("BrowserGlue terminateProcess - pid: %lu)", static_cast<unsigned long>(pid64));

    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        if (!env->IsSameObject(s_browserGlue_object, nullptr)) {
            jobject obj = env->NewLocalRef(s_browserGlue_object);
            jclass klass = env->GetObjectClass(obj);

            jmethodID methodID = env->GetMethodID(klass, "terminateProcess", "(J)V");
            if (methodID != nullptr) {
                env->CallVoidMethod(obj, methodID, static_cast<jlong>(pid64));
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    ALOGE("Cannot terminate process (exception occurred on Java side)");
                }
            } else
                ALOGE("Cannot terminate process (cannot find \"terminateProcess\" method)");

            env->DeleteLocalRef(klass);
            env->DeleteLocalRef(obj);
        } else
            ALOGE("Cannot terminate process (BrowserGlue has been garbage collected)");
    } catch (...) {
        ALOGE("Cannot terminate process (JNI environment error)");
    }
}

struct wpe_process_provider_interface processProviderInterface = {
    .create = createProcessProvider,
    .destroy = destroyProcessProvider,
    .launch = launchProcess,
    .terminate = terminateProcess,
};
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = Wpe::Android::initVM(vm);
    jclass klass = env->FindClass("com/wpe/wpe/BrowserGlue");
    if (klass == nullptr)
        return JNI_ERR;

    static const JNINativeMethod methods[] = {
        {"setupEnvironment", "([Ljava/lang/String;)V", reinterpret_cast<void*>(setupEnvironment)},
        {"init", "(Lcom/wpe/wpe/BrowserGlue;)V", reinterpret_cast<void*>(init)},
        {"initLooperHelper", "()V", reinterpret_cast<void*>(initLooperHelper)},
        {"shut", "()V", reinterpret_cast<void*>(shut)},
        {"newPage", "(Lcom/wpe/wpe/Page;III)V", reinterpret_cast<void*>(newPage)},
        {"closePage", "(I)V", reinterpret_cast<void*>(closePage)},
        {"loadURL", "(ILjava/lang/String;)V", reinterpret_cast<void*>(loadURL)},
        {"goBack", "(I)V", reinterpret_cast<void*>(goBack)}, {"goForward", "(I)V", reinterpret_cast<void*>(goForward)},
        {"stopLoading", "(I)V", reinterpret_cast<void*>(stopLoading)},
        {"reload", "(I)V", reinterpret_cast<void*>(reload)},
        {"surfaceCreated", "(ILandroid/view/Surface;)V", reinterpret_cast<void*>(surfaceCreated)},
        {"surfaceChanged", "(IIII)V", reinterpret_cast<void*>(surfaceChanged)},
        {"surfaceRedrawNeeded", "(I)V", reinterpret_cast<void*>(surfaceRedrawNeeded)},
        {"surfaceDestroyed", "(I)V", reinterpret_cast<void*>(surfaceDestroyed)},
        {"touchEvent", "(IJIFF)V", reinterpret_cast<void*>(touchEvent)},
        {"setZoomLevel", "(ID)V", reinterpret_cast<void*>(setZoomLevel)},
        {"setInputMethodContent", "(IC)V", reinterpret_cast<void*>(setInputMethodContent)},
        {"deleteInputMethodContent", "(II)V", reinterpret_cast<void*>(deleteInputMethodContent)},
        {"requestExitFullscreenMode", "(I)V", reinterpret_cast<void*>(requestExitFullscreenMode)},
        {"updateAllPageSettings", "(ILcom/wpe/wpe/PageSettings;)V", reinterpret_cast<void*>(updateAllPageSettings)}};
    int result = env->RegisterNatives(klass, methods, sizeof(methods) / sizeof(JNINativeMethod));
    env->DeleteLocalRef(klass);

    wpe_process_provider_register_interface(&processProviderInterface);

    return (result != JNI_OK) ? result : Wpe::Android::JNI_VERSION;
}
