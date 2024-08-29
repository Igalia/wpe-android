/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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

#include "Environment.h"
#include "Logging.h"

#include <cassert>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <string>
#include <unistd.h>

namespace {
void setupNativeEnvironment(JNIEnv* /*env*/, jclass /*klass*/, jstringArray envStringsArray) noexcept
{
    Logging::logDebug("setupNativeEnvironment()");
    Environment::configureEnvironment(envStringsArray);
}

void initializeNativeMain(JNIEnv* /*env*/, jclass /*klass*/, jlong pid, jint type, jint fileDesc) noexcept
{
    auto processType = static_cast<ProcessType>(type);

    // As this function can exclusively be called from JNI, we just
    // need to assert having the right value for the process type in
    // case that one day the Java enum is modified but the native enum
    // has not been well synchronized.
    assert(processType >= ProcessType::FirstType);
    assert(processType < ProcessType::TypesCount);

    Logging::pipeStdoutToLogcat();

    static constexpr const char* const processName[static_cast<int>(ProcessType::TypesCount)]
        = {"WPEWebProcess", "WPENetworkProcess", "WPEWebDriverProcess"};

    static constexpr const char* const entrypointName[static_cast<int>(ProcessType::TypesCount)]
        = {"android_WebProcess_main", "android_NetworkProcess_main", "android_WebDriverProcess_main"};

    using ProcessEntryPoint = int(int, char**);
    auto* entrypoint
        = reinterpret_cast<ProcessEntryPoint*>(dlsym(RTLD_DEFAULT, entrypointName[static_cast<int>(processType)]));
    Logging::logDebug("initializeNativeMain() for %s, fd: %d, entrypoint: %p",
        processName[static_cast<int>(processType)], fileDesc, entrypoint);

    static constexpr size_t NUMBER_BUFFER_SIZE = 32;
    char arg1String[NUMBER_BUFFER_SIZE];
    char arg2String[NUMBER_BUFFER_SIZE];
    char arg3String[NUMBER_BUFFER_SIZE];

    int numArgs = 3;

    if (processType != ProcessType::WebDriverProcess) {
        (void)snprintf(arg1String, NUMBER_BUFFER_SIZE, "%" PRIu64, static_cast<uint64_t>(pid));
        (void)snprintf(arg2String, NUMBER_BUFFER_SIZE, "%d", fileDesc);

    } else {
        numArgs = 4;

        (void)snprintf(arg1String, NUMBER_BUFFER_SIZE, "--port=8888");
        (void)snprintf(arg2String, NUMBER_BUFFER_SIZE, "--host=all");
        (void)snprintf(arg3String, NUMBER_BUFFER_SIZE, "--target=127.0.0.1:%d", static_cast<uint32_t>(pid));
    }

    char* argv[numArgs];
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-const-cast)
    argv[0] = const_cast<char*>(processName[static_cast<int>(processType)]);
    argv[1] = arg1String;
    argv[2] = arg2String;
    if (numArgs > 3) {
        argv[3] = arg3String;
    }

    (*entrypoint)(numArgs, argv);
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVM, void* /*reserved*/)
{
    try {
        JNI::initVM(javaVM);

        JNI::Class("org/wpewebkit/wpe/services/WPEService")
            .registerNativeMethods(
                JNI::StaticNativeMethod<void(jstringArray)>("setupNativeEnvironment", setupNativeEnvironment),
                JNI::StaticNativeMethod<void(jlong, jint, jint)>("initializeNativeMain", initializeNativeMain));

        return JNI::VERSION;
    } catch (...) {
        return JNI_ERR;
    }
}
