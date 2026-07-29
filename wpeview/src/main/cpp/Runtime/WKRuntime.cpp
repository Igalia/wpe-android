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

#include <string>
#include <wpe/webkit.h>

/***********************************************************************************************************************
 * JNI mapping with Java WKRuntime class
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNIBrowser, "org/wpewebkit/wpe/WKRuntime");

class JNIBrowserCache final : public JNI::TypedClass<JNIBrowser> {
public:
    JNIBrowserCache();

    bool launchProcess(jlong pid, jint type, jint fileDesc) const noexcept
    {
        try {
            m_launchProcessMethod.invoke(m_browserJavaInstance.get(), pid, type, fileDesc);
            return true;
        } catch (const std::exception& ex) {
            Logging::logError("Cannot launch process: %s", ex.what());
            return false;
        }
    }

    void terminateProcess(jlong pid) const noexcept
    {
        try {
            m_terminateProcessMethod.invoke(m_browserJavaInstance.get(), pid);
        } catch (const std::exception& ex) {
            Logging::logError("Cannot terminate process: %s", ex.what());
        }
    }

private:
    mutable JNI::ProtectedType<JNIBrowser> m_browserJavaInstance;

    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jlong, jint, jint)> m_launchProcessMethod;
    const JNI::Method<void(jlong)> m_terminateProcessMethod;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

const JNIBrowserCache& getJNIBrowserCache()
{
    static const JNIBrowserCache s_singleton;
    return s_singleton;
}

JNIBrowserCache::JNIBrowserCache()
    : JNI::TypedClass<JNIBrowser>(true)
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
                getJNIBrowserCache().m_browserJavaInstance
                    = JNI::createTypedProtectedRef(env, reinterpret_cast<JNIBrowser>(obj), true);
                WKRuntime::instance().jniInit();
            }),
        JNI::NativeMethod<void()>(
            "nativeShut", +[](JNIEnv*, jobject) {
                WKRuntime::instance().jniShut();
                getJNIBrowserCache().m_browserJavaInstance = nullptr;
            }));
}

/***********************************************************************************************************************
 * Process launch/terminate bridge to the Java WKRuntime (used by WPEProcessManagerAndroid)
 **********************************************************************************************************************/

bool wkRuntimeLaunchProcess(int64_t processId, int processType, int ipcSocketFd) noexcept
{
    return getJNIBrowserCache().launchProcess(processId, processType, ipcSocketFd);
}

void wkRuntimeTerminateProcess(int64_t processId) noexcept
{
    getJNIBrowserCache().terminateProcess(processId);
}

WKRuntime::WKRuntime()
{
    Logging::logDebug("WKRuntime [tid %d], WPE WebKit %u.%u.%u", gettid(), webkit_get_major_version(),
        webkit_get_minor_version(), webkit_get_micro_version());
}

void WKRuntime::configureJNIMappings()
{
    getJNIBrowserCache();
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

void WKRuntime::invokeOnUiThread(void (*onExec)(void*), void (*onDestroy)(void*), void* userData) const noexcept
{
    m_messagePump->invoke(onExec, onDestroy, userData);
}
