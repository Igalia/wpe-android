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
#include "Init.h"
#include "Logging.h"

#include <cassert>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <string>
#include <sys/system_properties.h>
#include <unistd.h>

namespace {
void setupNativeEnvironment(JNIEnv* /*env*/, jclass /*klass*/, jstringArray envStringsArray) noexcept
{
    Logging::logDebug("setupNativeEnvironment()");
    Environment::configureEnvironment(envStringsArray);
}

// The GPU driver keeps the programs it compiles in Android's EGL blob cache, which persists them only once
// a cache file is set: ActivityThread does that for apps that render with HWUI, but the web process never
// had one. Skia's own program cache hands the driver its program binaries, yet the Mali driver still spent
// 10-20 ms finishing each program the first time a new web process used it, inside a fling. The cache
// file is set through libEGL's exported egl_cache_t, as HardwareRenderer.setupDiskCache() does.
// debug.wpe.egl.blobcache=0 leaves it unset.
void setUpEGLBlobCache() noexcept
{
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.wpe.egl.blobcache", value) > 0 && value[0] == '0')
        return;
    // Runs before WebKit starts any thread.
    const char* cacheHome = getenv("XDG_CACHE_HOME"); // NOLINT(concurrency-mt-unsafe)
    if (!cacheHome)
        return;
    void* egl = dlopen("libEGL.so", RTLD_NOW);
    if (!egl)
        return;
    using GetCacheFunction = void* (*)();
    using SetCacheFilenameFunction = void (*)(void*, const char*);
    auto* getCache = reinterpret_cast<GetCacheFunction>(dlsym(egl, "_ZN7android11egl_cache_t3getEv"));
    auto* setCacheFilename
        = reinterpret_cast<SetCacheFilenameFunction>(dlsym(egl, "_ZN7android11egl_cache_t16setCacheFilenameEPKc"));
    if (!getCache || !setCacheFilename)
        return;
    std::string path = std::string(cacheHome) + "/com.android.opengl.shaders_cache";
    setCacheFilename(getCache(), path.c_str());
    Logging::logDebug("EGL blob cache: %s", path.c_str());
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

    if (processType == ProcessType::WebProcess)
        setUpEGLBlobCache();

    static constexpr const char* const processName[static_cast<int>(ProcessType::TypesCount)]
        = {"WPEWebProcess", "WPENetworkProcess", "WPEWebDriverProcess"};

    // Mangled C++ symbols for WebKit::{Web,Network}ProcessMain(int, char**)
    static constexpr const char* const entrypointName[static_cast<int>(ProcessType::TypesCount)]
        = {"_ZN6WebKit14WebProcessMainEiPPc", "_ZN6WebKit18NetworkProcessMainEiPPc",
            "_ZN6WebKit20WebDriverProcessMainEiPPc"};

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

    // TODO NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
    auto argv = new char*[numArgs];
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-const-cast, cppcoreguidelines-owning-memory)
    argv[0] = const_cast<char*>(processName[static_cast<int>(processType)]);
    argv[1] = arg1String;
    argv[2] = arg2String;
    if (numArgs > 3) {
        argv[3] = arg3String;
    }

    (*entrypoint)(numArgs, argv);
    // TODO NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
    delete[] argv;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVM, void* /*reserved*/)
{
    Init::initialize(javaVM);

    JNI::Class("org/wpewebkit/wpe/services/WPEService")
        .registerNativeMethods(
            JNI::StaticNativeMethod<void(jstringArray)>("setupNativeEnvironment", setupNativeEnvironment),
            JNI::StaticNativeMethod<void(jlong, jint, jint)>("initializeNativeMain", initializeNativeMain));

    return JNI::VERSION;
}
