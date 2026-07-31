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

#include "WKRuntime.h"

#include "Environment.h"
#include "Logging.h"
#include "LooperThread.h"

#include <wpe/webkit.h>

/***********************************************************************************************************************
 * JNI mapping with Java WKRuntime class
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNIWPERuntime, "org/wpewebkit/wpe/WKRuntime");

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
    registerNativeMethods(JNI::StaticNativeMethod<void()>(
                              "startNativeLooper",
                              +[](JNIEnv* /*env*/, jclass /*klass*/) {
                                  Logging::logDebug("WKRuntime::startNativeLooper() [tid %d]", gettid());
                                  LooperThread::instance().startLooper();
                              }),
        JNI::StaticNativeMethod<void(jstringArray)>(
            "setupNativeEnvironment",
            +[](JNIEnv* /*env*/, jclass /*klass*/, jstringArray envStringsArray) {
                Logging::logDebug("WKRuntime::setupNativeEnvironment() [tid %d]", gettid());
                Logging::pipeStdoutToLogcat();
                Environment::configureEnvironment(envStringsArray);
            }),
        JNI::NativeMethod<void()>(
            "nativeInit",
            +[](JNIEnv* env, jobject obj) {
                getJNIWPERuntimeCache().m_runtimeJavaInstance
                    = JNI::createTypedProtectedRef(env, reinterpret_cast<JNIWPERuntime>(obj), true);
                WKRuntime::instance().jniInit();
            }),
        JNI::NativeMethod<void()>(
            "nativeShut", +[](JNIEnv*, jobject) {
                WKRuntime::instance().jniShut();
                getJNIWPERuntimeCache().m_runtimeJavaInstance = nullptr;
            }));
}

/***********************************************************************************************************************
 * Process launch/terminate bridge to the Java WKRuntime (used by WPEProcessManagerAndroid)
 **********************************************************************************************************************/

bool wkRuntimeLaunchProcess(int64_t processId, int processType, int ipcSocketFd) noexcept
{
    return getJNIWPERuntimeCache().launchProcess(processId, processType, ipcSocketFd);
}

void wkRuntimeTerminateProcess(int64_t processId) noexcept
{
    getJNIWPERuntimeCache().terminateProcess(processId);
}

WKRuntime::WKRuntime()
{
    Logging::logDebug("WKRuntime [tid %d], WPE WebKit %u.%u.%u", gettid(), webkit_get_major_version(),
        webkit_get_minor_version(), webkit_get_micro_version());
}

void WKRuntime::configureJNIMappings()
{
    getJNIWPERuntimeCache();
}

void WKRuntime::jniInit()
{
    Logging::logDebug("WKRuntime::jniInit() [tid %d]", gettid());
    m_messagePump = std::make_unique<MessagePump>();
}

void WKRuntime::jniShut() noexcept
{
    try {
        Logging::logDebug("WKRuntime::jniShut() [tid %d]", gettid());
        m_messagePump = nullptr;
        // TODO NOLINTNEXTLINE(bugprone-empty-catch)
    } catch (...) {
    }
}
