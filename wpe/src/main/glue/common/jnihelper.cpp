#include <jnihelper.h>

bool getJniEnv(JNIEnv **env) {
    bool didAttachThread = false;
    *env = nullptr;
    auto get_env_result = vm->GetEnv((void**)env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(env, NULL) == JNI_OK) {
            didAttachThread = true;
        } else {
            throw;
        }
    } else if (get_env_result == JNI_EVERSION) {
        // Unsupported JNI version.
        throw;
    }
    return didAttachThread;
}
