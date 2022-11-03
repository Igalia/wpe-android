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

#include "Browser.h"

#include "Environment.h"
#include "Logging.h"
#include "LooperThread.h"

#include <string>

/***********************************************************************************************************************
 * JNI mapping with Java Browser class
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNIBrowser, "com/wpe/wpe/Browser");

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

    const JNI::Method<void(jlong, jint, jint)> m_launchProcessMethod;
    const JNI::Method<void(jlong)> m_terminateProcessMethod;
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
                                  Logging::logDebug("Browser::startNativeLooper() [tid %d]", gettid());
                                  LooperThread::instance().startLooper();
                              }),
        JNI::StaticNativeMethod<void(jstringArray)>(
            "setupNativeEnvironment",
            +[](JNIEnv* /*env*/, jclass /*klass*/, jstringArray envStringsArray) {
                Logging::logDebug("Browser::setupNativeEnvironment() [tid %d]", gettid());
                Logging::pipeStdoutToLogcat();
                Environment::configureEnvironment(envStringsArray);
            }),
        JNI::NativeMethod<void(jstring, jstring)>(
            "nativeInit",
            +[](JNIEnv* env, jobject obj, jstring dataDir, jstring cacheDir) {
                getJNIBrowserCache().m_browserJavaInstance
                    = JNI::createTypedProtectedRef(env, reinterpret_cast<JNIBrowser>(obj), true);
                Browser::instance().jniInit(
                    JNI::String(dataDir).getContent().get(), JNI::String(cacheDir).getContent().get());
            }),
        JNI::NativeMethod<void()>(
            "nativeShut", +[](JNIEnv*, jobject) {
                Browser::instance().jniShut();
                getJNIBrowserCache().m_browserJavaInstance = nullptr;
            }));
}

namespace {
/***********************************************************************************************************************
 * WPE process provider functions
 **********************************************************************************************************************/

int64_t wpeLaunchProcess(void* /*backend*/, wpe_process_type wpeProcessType, void* userData) noexcept
{
    Logging::logDebug("wpeLaunchProcess(%d, %p) [tid %d]", wpeProcessType, userData, gettid());
    auto** options = reinterpret_cast<char**>(userData);
    if ((options == nullptr) || (options[0] == nullptr) || (options[1] == nullptr))
        return -1;

    jlong pid = std::strtoll(options[0], nullptr, 10); // NOLINT(cppcoreguidelines-avoid-magic-numbers)
    jint fileDesc = std::stoi(options[1]);

    auto processType = ProcessType::TypesCount;
    if (wpeProcessType == WPE_PROCESS_TYPE_WEB) {
        processType = ProcessType::WebProcess;
        Logging::logVerbose("Launching WebProcess");
    } else if (wpeProcessType == WPE_PROCESS_TYPE_NETWORK) {
        processType = ProcessType::NetworkProcess;
        Logging::logVerbose("Launching NetworkProcess");
    }

    if ((processType < ProcessType::FirstType) || (processType >= ProcessType::TypesCount)) {
        Logging::logError("Cannot launch process (invalid process type: %d)", static_cast<int>(processType));
        return -1;
    }

    return getJNIBrowserCache().launchProcess(pid, static_cast<jint>(processType), fileDesc) ? 0 : -1;
}

void wpeTerminateProcess(void* /*backend*/, int64_t pid)
{
    Logging::logDebug("wpeTerminateProcess(%ld) [tid %d]", pid, gettid());
    getJNIBrowserCache().terminateProcess(pid);
}
} // namespace

void Browser::configureJNIMappings()
{
    getJNIBrowserCache();

    static const wpe_process_provider_interface s_processProviderInterface
        = {.create = nullptr, .destroy = nullptr, .launch = wpeLaunchProcess, .terminate = wpeTerminateProcess};
    wpe_process_provider_register_interface(&s_processProviderInterface);
}

void Browser::jniInit(const char* dataDir, const char* cacheDir)
{
    Logging::logDebug("Browser::jniInit('%s', '%s') [tid %d]", dataDir, cacheDir, gettid());

    m_messagePump = std::make_unique<MessagePump>();
    m_websiteDataManager
        = {webkit_website_data_manager_new("base-data-directory", dataDir, "base-cache-directory", cacheDir, nullptr),
            [](auto* ptr) { g_object_unref(ptr); }};
    m_webContext = {webkit_web_context_new_with_website_data_manager(m_websiteDataManager.get()),
        [](auto* ptr) { g_object_unref(ptr); }};
}

void Browser::jniShut() noexcept
{
    try {
        Logging::logDebug("Browser::jniShut() [tid %d]", gettid());
        m_messagePump = nullptr;
        m_websiteDataManager = nullptr;
        m_webContext = nullptr;
    } catch (...) {
    }
}

void Browser::invokeOnUiThread(void (*onExec)(void*), void (*onDestroy)(void*), void* userData) const noexcept
{
    m_messagePump->invoke(onExec, onDestroy, userData);
}
