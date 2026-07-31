/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "WPERuntime.h"

#include "Environment.h"
#include "Logging.h"
#include "MessagePump.h"

#include <memory>
#include <unistd.h>

/***********************************************************************************************************************
 * JNI mapping with Java WPERuntime class
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNIWPERuntime, "org/wpewebkit/wpe/WPERuntime");

class JNIWPERuntimeCache final : public JNI::TypedClass<JNIWPERuntime> {
public:
    JNIWPERuntimeCache();

    bool launchProcess(jlong pid, jint type, jint fileDesc) const noexcept
    {
        try {
            m_launchProcessMethod.invoke(m_runtimeJavaInstance.get(), pid, type, fileDesc);
            return true;
        } catch (const std::exception& ex) {
            Logging::logError("Cannot launch process: %s", ex.what());
            return false;
        }
    }

    void terminateProcess(jlong pid) const noexcept
    {
        try {
            m_terminateProcessMethod.invoke(m_runtimeJavaInstance.get(), pid);
        } catch (const std::exception& ex) {
            Logging::logError("Cannot terminate process: %s", ex.what());
        }
    }

private:
    mutable JNI::ProtectedType<JNIWPERuntime> m_runtimeJavaInstance;
    // The MessagePump attaching the GLib main context to the Android main looper, alive between
    // the Java WPERuntime nativeInit() and nativeShut() calls.
    mutable std::unique_ptr<MessagePump> m_messagePump;

    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jlong, jint, jint)> m_launchProcessMethod;
    const JNI::Method<void(jlong)> m_terminateProcessMethod;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

static const JNIWPERuntimeCache& getJNIWPERuntimeCache()
{
    static const JNIWPERuntimeCache s_singleton;
    return s_singleton;
}

JNIWPERuntimeCache::JNIWPERuntimeCache()
    : JNI::TypedClass<JNIWPERuntime>(true)
    , m_launchProcessMethod(getMethod<void(jlong, jint, jint)>("launchProcess"))
    , m_terminateProcessMethod(getMethod<void(jlong)>("terminateProcess"))
{
    registerNativeMethods(JNI::StaticNativeMethod<void(jstringArray)>(
                              "setupNativeEnvironment",
                              +[](JNIEnv* /*env*/, jclass /*klass*/, jstringArray envStringsArray) {
                                  Logging::logDebug("WPERuntime::setupNativeEnvironment() [tid %d]", gettid());
                                  Logging::pipeStdoutToLogcat();
                                  Environment::configureEnvironment(envStringsArray);
                              }),
        JNI::NativeMethod<void()>(
            "nativeInit",
            +[](JNIEnv* env, jobject obj) {
                Logging::logDebug("WPERuntime::nativeInit() [tid %d]", gettid());
                const auto& cache = getJNIWPERuntimeCache();
                cache.m_runtimeJavaInstance
                    = JNI::createTypedProtectedRef(env, reinterpret_cast<JNIWPERuntime>(obj), true);
                cache.m_messagePump = std::make_unique<MessagePump>();
            }),
        JNI::NativeMethod<void()>(
            "nativeShut", +[](JNIEnv*, jobject) {
                Logging::logDebug("WPERuntime::nativeShut() [tid %d]", gettid());
                const auto& cache = getJNIWPERuntimeCache();
                cache.m_messagePump = nullptr;
                cache.m_runtimeJavaInstance = nullptr;
            }));
}

/***********************************************************************************************************************
 * Process launch/terminate bridge to the Java WPERuntime (used by WPEProcessManagerAndroid)
 **********************************************************************************************************************/

bool wpeRuntimeLaunchProcess(int64_t processId, int processType, int ipcSocketFd) noexcept
{
    return getJNIWPERuntimeCache().launchProcess(processId, processType, ipcSocketFd);
}

void wpeRuntimeTerminateProcess(int64_t processId) noexcept
{
    getJNIWPERuntimeCache().terminateProcess(processId);
}

void WPERuntime::configureJNIMappings()
{
    getJNIWPERuntimeCache();
}
