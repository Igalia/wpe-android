#include "exportedbuffer.h"
#include "jnihelper.h"
#include "logging.h"
#include "pageeventobserver.h"

// FIXME: these macros only work for single argument functions, which is what we have so far.

#define VA_LIST(...) __VA_ARGS__

#define JAVA_CALL_START(METHOD, FORMAT, PARAM) \
  void PageEventObserver::METHOD(PARAM) { \
    try { \
      JNIEnv *env = ScopedEnv(vm).getEnv(); \
      jmethodID _method = env->GetMethodID(pageClass, #METHOD, FORMAT); \
      if (_method == nullptr) { \
        throw; \
      }

#define JAVA_CALL_NO_CAST(ARG) \
    env->CallVoidMethod(pageObj, _method, ARG);

#define JAVA_CALL_WITH_CAST(ARG, TYPE, CASTFUN) \
    TYPE arg = env->CASTFUN(ARG); \
    env->CallVoidMethod(pageObj, _method, arg);

#define JAVA_CALL_END() \
    } catch(int) { \
      ALOGE("Could not send event"); \
    } \
}

#define JAVA_CALL_NO_PARAMS(METHOD) \
  void PageEventObserver::METHOD() { \
    try { \
      JNIEnv *env = ScopedEnv(vm).getEnv(); \
      jmethodID _method = env->GetMethodID(pageClass, #METHOD, "()V"); \
      if (_method == nullptr) { \
        throw; \
      } \
      env->CallVoidMethod(pageObj, _method); \
    } catch(int) { \
          ALOGE("Could not send event"); \
        } \
    }


#define JAVA_CALL(METHOD, FORMAT, PARAM, ARG) \
    JAVA_CALL_START(METHOD, FORMAT, PARAM) \
    JAVA_CALL_NO_CAST(ARG) \
    JAVA_CALL_END()

#define JAVA_CALL_CAST(METHOD, FORMAT, PARAM, ARG, TYPE, CASTFUN) \
    JAVA_CALL_START(METHOD, FORMAT, PARAM) \
    JAVA_CALL_WITH_CAST(ARG, TYPE, CASTFUN) \
    JAVA_CALL_END()

PageEventObserver::~PageEventObserver() {
    try {
        JNIEnv *env = ScopedEnv(vm).getEnv();
        env->DeleteGlobalRef(pageClass);
        env->DeleteGlobalRef(pageObj);
    } catch (int) {}
}

JAVA_CALL(onLoadChanged, "(I)V", WebKitLoadEvent loadEvent, (int) loadEvent)
JAVA_CALL(onLoadProgress, "(D)V", double progress, progress)
JAVA_CALL_CAST(onUriChanged, "(Ljava/lang/String;)V", const char *uri, uri, jstring, NewStringUTF)
JAVA_CALL_NO_PARAMS(onInputMethodContextIn)
JAVA_CALL_NO_PARAMS(onInputMethodContextOut)

void PageEventObserver::onTitleChanged(const char* title, gboolean canGoBack, gboolean canGoForward) {
    try {
        JNIEnv *env = ScopedEnv(vm).getEnv();
        jmethodID _method = env->GetMethodID(pageClass, "onTitleChanged", "(Ljava/lang/String;ZZ)V");
        if (_method == nullptr) {
            throw;
        }

        jstring jtitle = env->NewStringUTF(title);
        env->CallVoidMethod(pageObj, _method, jtitle, (jboolean)canGoBack, (jboolean)canGoForward);
    } catch(int) {
      ALOGE("Could not send event");
    }
}