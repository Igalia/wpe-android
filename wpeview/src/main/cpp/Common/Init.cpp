/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
 *   Author: Adrian Perez de Castro <aperez@igalia.com>
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

#include "Init.h"
#include "Logging.h"
#include <gst/gst.h>
#include <wpe/webkit.h>

static void initializeWKVersions()
{
    auto klass = JNI::Class("org/wpewebkit/wpe/WKVersions");
    g_autofree char* webkitVersion = g_strdup_printf(
        "%u.%u.%u", webkit_get_major_version(), webkit_get_minor_version(), webkit_get_micro_version());

    klass.getStaticField<jstring>("WEBKIT").setValue(JNI::String(webkitVersion));
    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jint>("WEBKIT_MAJOR").setValue(webkit_get_major_version());
    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jint>("WEBKIT_MINOR").setValue(webkit_get_minor_version());
    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jint>("WEBKIT_MICRO").setValue(webkit_get_micro_version());

    g_autofree char* gstVersion = gst_version_string();
    // TODO NOLINTNEXTLINE(cppcoreguidelines-init-variables, cppcoreguidelines-owning-memory)
    unsigned gstMajor, gstMinor, gstMicro, gstNano;
    gst_version(&gstMajor, &gstMinor, &gstMicro, &gstNano);

    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jstring>("GSTREAMER").setValue(JNI::String(gstVersion));
    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jint>("GSTREAMER_MAJOR").setValue(gstMajor);
    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jint>("GSTREAMER_MINOR").setValue(gstMinor);
    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jint>("GSTREAMER_MICRO").setValue(gstMicro);
    // TODO NOLINTNEXTLINE(bugprone-narrowing-conversions, cppcoreguidelines-narrowing-conversions)
    klass.getStaticField<jint>("GSTREAMER_NANO").setValue(gstNano);

    Logging::logVerbose("WKRuntime::Init: WPE WebKit %s, GStreamer %s", webkitVersion, gstVersion);
}

DECLARE_JNI_CLASS_SIGNATURE(JNIActivity, "android/app/Activity");
DECLARE_JNI_CLASS_SIGNATURE(JNIContext, "android/content/Context");

// TODO NOLINTNEXTLINE(readability-identifier-naming)
static jobject s_currentActivity {nullptr};

// TODO NOLINTNEXTLINE(readability-identifier-naming)
static jobject s_applicationContext {nullptr};

// TODO NOLINTNEXTLINE(readability-identifier-naming)
extern "C" __attribute__((visibility("default"))) jobject wpe_android_runtime_get_current_activity()
{
    Logging::logDebug("wpe_android_runtime_get_current_activity -> %p", s_currentActivity);
    return s_currentActivity;
}

// TODO NOLINTNEXTLINE(readability-identifier-naming)
extern "C" __attribute__((visibility("default"))) jobject wpe_android_runtime_get_application_context()
{
    Logging::logDebug("wpe_android_runtime_get_application_context -> %p", s_applicationContext);
    return s_applicationContext;
}

static void initializeWKActivityObserver()
{
    auto klass = JNI::Class("org/wpewebkit/wpe/WKActivityObserver");
    if (!klass) {
        Logging::logDebug("Init::initializeWKActivityObserver: No observer class, skipping.");
        return;
    }

    klass.registerNativeMethods(
        JNI::StaticNativeMethod<void(JNIActivity)>(
            "handleActivityStarted",
            +[](JNIEnv* env, jclass, JNIActivity activity) {
                Logging::logDebug("WKActivityObserver::handleActivityStarted(%p) current=%p [tid %d]", activity,
                    s_currentActivity, gettid());
                if (s_currentActivity != nullptr)
                    env->DeleteGlobalRef(s_currentActivity);
                s_currentActivity = activity ? static_cast<jobject>(env->NewGlobalRef(activity)) : nullptr;
            }),
        JNI::StaticNativeMethod<void(JNIActivity)>(
            "handleActivityStopped", +[](JNIEnv* env, jclass, JNIActivity activity) {
                Logging::logDebug("WKActivityObserver::handleActivityStopped(%p) current=%p [tid %d]", activity,
                    s_currentActivity, gettid());
                if (s_currentActivity != nullptr)
                    env->DeleteGlobalRef(s_currentActivity);
                s_currentActivity = nullptr;
            }));
}

static void initializeWKRuntime()
{
    auto klass = JNI::Class("org/wpewebkit/wpe/WKRuntime");
    if (!klass) {
        Logging::logDebug("Init::initializeWKRuntime: No runtime class, skipping.");
        return;
    }

    klass.registerNativeMethods(JNI::StaticNativeMethod<void(JNIContext)>(
        "setApplicationContext", +[](JNIEnv* env, jclass, JNIContext context) {
            Logging::logDebug("WKRuntime::setApplicationContext(%p) [tid %d]", context, gettid());
            if (s_applicationContext != nullptr)
                env->DeleteGlobalRef(s_applicationContext);
            s_applicationContext = context ? static_cast<jobject>(env->NewGlobalRef(context)) : nullptr;
        }));
}

JNIEnv* Init::initialize(JavaVM* javaVM)
{
    auto* env = JNI::initVM(javaVM);
    initializeWKVersions();
    initializeWKActivityObserver();
    initializeWKRuntime();
    return env;
}
