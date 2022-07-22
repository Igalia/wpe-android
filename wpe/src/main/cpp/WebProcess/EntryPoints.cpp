#include "Service.h"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
    return Wpe::Android::registerServiceEntryPoints(vm, "com/wpe/wpe/services/WebProcessService");
}
