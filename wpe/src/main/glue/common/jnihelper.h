#pragma once

#include <jni.h>

namespace wpe {
namespace android {

void InitVM(JavaVM* vm);

JNIEnv* AttachCurrentThread();

} // namespace android
} // namespace wpe

static bool getJniEnv(JavaVM *vm, JNIEnv **env) {
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

class ScopedEnv {
public:
    ScopedEnv(JavaVM *vm) : vm(vm), attachedToVm(false) {
        attachedToVm = getJniEnv(vm, &env);
    }

    ScopedEnv(const ScopedEnv&) = delete;
    ScopedEnv& operator=(const ScopedEnv&) = delete;

    virtual ~ScopedEnv() {
        if (attachedToVm) {
            vm->DetachCurrentThread();
            attachedToVm = false;
        }
    }

    JNIEnv *getEnv() const { return env; }

private:
    bool attachedToVm;
    JavaVM *vm;
    JNIEnv *env;
};

