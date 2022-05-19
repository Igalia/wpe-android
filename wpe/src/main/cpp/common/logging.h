#pragma once

#include <android/log.h>

#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WPE Glue", __VA_ARGS__)
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "WPE Glue", __VA_ARGS__)

namespace wpe { namespace android {

bool pipeStdoutToLogcat();

}} // namespace wpe::android
