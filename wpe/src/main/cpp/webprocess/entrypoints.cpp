#include "service.h"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
    return wpe::android::registerServiceEntryPoints(vm, "com/wpe/wpe/services/WebProcessGlue");
}
