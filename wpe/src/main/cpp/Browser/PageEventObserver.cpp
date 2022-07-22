#include "PageEventObserver.h"

#include "JNIHelper.h"
#include "Logging.h"

#include <stdexcept>

namespace {
enum class JavaMethodTag : int
{
    ON_LOAD_CHANGED = 0,
    ON_LOAD_PROGRESS,
    ON_URI_CHANGED,
    ON_TITLE_CHANGED,
    ON_INPUT_METHOD_CONTEXT_IN,
    ON_INPUT_METHOD_CONTEXT_OUT,
    ENTER_FULLSCREEN_MODE,
    EXIT_FULLSCREEN_MODE
};

struct JavaMethodDesc {
    const char* name;
    const char* signature;
};

constexpr JavaMethodDesc javaMethods[PageEventObserver::NB_JAVA_METHODS]
    = {{"onLoadChanged", "(I)V"}, {"onLoadProgress", "(D)V"}, {"onUriChanged", "(Ljava/lang/String;)V"},
        {"onTitleChanged", "(Ljava/lang/String;ZZ)V"}, {"onInputMethodContextIn", "()V"},
        {"onInputMethodContextOut", "()V"}, {"enterFullscreenMode", "()V"}, {"exitFullscreenMode", "()V"}};
}; // namespace

PageEventObserver::PageEventObserver(JNIEnv* env, jclass klass, jobject obj)
{
    m_pageClass = reinterpret_cast<jclass>(env->NewGlobalRef(klass));
    m_pageObj = env->NewGlobalRef(obj);

    for (int i = 0; i < NB_JAVA_METHODS; ++i) {
        auto& methodDesc = javaMethods[i];
        m_javaMethodIDs[i] = env->GetMethodID(klass, methodDesc.name, methodDesc.signature);
        if (m_javaMethodIDs[i] == nullptr)
            ALOGE("Cannot find Java method \"%s\" in provided Java class", methodDesc.name);
    }
}

PageEventObserver::~PageEventObserver()
{
    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        env->DeleteGlobalRef(m_pageObj);
        env->DeleteGlobalRef(m_pageClass);
    } catch (...) {
        ALOGE("Memory leak: cannot release page instance and class Java global references");
    }
}

template <typename... Args> void PageEventObserver::callJavaVoidMethod(int methodIdx, Args&&... args)
{
    try {
        jmethodID methodID = m_javaMethodIDs[methodIdx];
        if (methodID == nullptr)
            throw std::runtime_error("Invalid Java method ID");

        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        env->CallVoidMethod(m_pageObj, methodID, args...);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            throw std::runtime_error("Java exception from JNI call");
        }
    } catch (...) {
        ALOGE("Cannot send \"%s\" page event", javaMethods[methodIdx].name);
    }
}

void PageEventObserver::onLoadChanged(WebKitLoadEvent loadEvent)
{
    callJavaVoidMethod(static_cast<int>(JavaMethodTag::ON_LOAD_CHANGED), static_cast<jint>(loadEvent));
}

void PageEventObserver::onLoadProgress(double progress)
{
    callJavaVoidMethod(static_cast<int>(JavaMethodTag::ON_LOAD_PROGRESS), static_cast<jdouble>(progress));
}

void PageEventObserver::onUriChanged(const char* uri)
{
    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        jstring jstr = env->NewStringUTF(uri);
        callJavaVoidMethod(static_cast<int>(JavaMethodTag::ON_URI_CHANGED), jstr);
        env->DeleteLocalRef(jstr);
    } catch (...) {
        ALOGE("Cannot send \"onUriChanged\" page event");
    }
}

void PageEventObserver::onTitleChanged(const char* title, gboolean canGoBack, gboolean canGoForward)
{
    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        jstring jstr = env->NewStringUTF(title);
        callJavaVoidMethod(static_cast<int>(JavaMethodTag::ON_TITLE_CHANGED), jstr, static_cast<jboolean>(canGoBack),
            static_cast<jboolean>(canGoForward));
        env->DeleteLocalRef(jstr);
    } catch (...) {
        ALOGE("Cannot send \"onTitleChanged\" page event");
    }
}

void PageEventObserver::onInputMethodContextIn()
{
    callJavaVoidMethod(static_cast<int>(JavaMethodTag::ON_INPUT_METHOD_CONTEXT_IN));
}

void PageEventObserver::onInputMethodContextOut()
{
    callJavaVoidMethod(static_cast<int>(JavaMethodTag::ON_INPUT_METHOD_CONTEXT_OUT));
}

void PageEventObserver::enterFullscreenMode()
{
    callJavaVoidMethod(static_cast<int>(JavaMethodTag::ENTER_FULLSCREEN_MODE));
}

void PageEventObserver::exitFullscreenMode()
{
    callJavaVoidMethod(static_cast<int>(JavaMethodTag::EXIT_FULLSCREEN_MODE));
}
