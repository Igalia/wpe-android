#include "jnihelper.h"

#include <cstdlib>
#include <sys/prctl.h>

#include "logging.h"

namespace wpe {
namespace android {
namespace {

JavaVM *g_jvm = NULL;

}

void InitVM(JavaVM *vm) {
    g_jvm = vm;
}

JNIEnv *AttachCurrentThread() {
    JNIEnv *env = nullptr;
    jint ret = g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED || !env) {
        JavaVMAttachArgs args = { JNI_VERSION_1_6, nullptr, nullptr };

        char thread_name[16]; // 16 is the max size for android thread names
        int err = prctl(PR_GET_NAME, thread_name);
        if (err >= 0) {
            args.name = thread_name;
        }

        if (g_jvm->AttachCurrentThread(&env, &args) != JNI_OK) {
            ALOGE("Failed to attach thread");
            std::abort();
        }
    }
    return env;
}

} // namespace android
} // namespace wpe
