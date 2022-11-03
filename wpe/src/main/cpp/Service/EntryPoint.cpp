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
        = {"WPEWebProcess", "WPENetworkProcess"};

    static constexpr const char* const entrypointName[static_cast<int>(ProcessType::TypesCount)]
        = {"android_WebProcess_main", "android_NetworkProcess_main"};

    using ProcessEntryPoint = int(int, char**);
    auto* entrypoint
        = reinterpret_cast<ProcessEntryPoint*>(dlsym(RTLD_DEFAULT, entrypointName[static_cast<int>(processType)]));
    Logging::logDebug("initializeNativeMain() for %s, fd: %d, entrypoint: %p",
        processName[static_cast<int>(processType)], fileDesc, entrypoint);

    static constexpr size_t NUMBER_BUFFER_SIZE = 32;
    char pidString[NUMBER_BUFFER_SIZE];
    (void)snprintf(pidString, NUMBER_BUFFER_SIZE, "%" PRIu64, static_cast<uint64_t>(pid));
    char fdString[NUMBER_BUFFER_SIZE];
    (void)snprintf(fdString, NUMBER_BUFFER_SIZE, "%d", fileDesc);

    char* argv[3];
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-const-cast)
    argv[0] = const_cast<char*>(processName[static_cast<int>(processType)]);
    argv[1] = pidString;
    argv[2] = fdString;
    (*entrypoint)(3, argv);
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVM, void* /*reserved*/)
{
    try {
        JNI::initVM(javaVM);

        JNI::Class("com/wpe/wpe/services/WPEService")
            .registerNativeMethods(
                JNI::StaticNativeMethod<void(jstringArray)>("setupNativeEnvironment", setupNativeEnvironment),
                JNI::StaticNativeMethod<void(jlong, jint, jint)>("initializeNativeMain", initializeNativeMain));

        return JNI::VERSION;
    } catch (...) {
        return JNI_ERR;
    }
}
