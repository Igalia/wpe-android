#pragma once

#include <android/log.h>

#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "libwpe-android", __VA_ARGS__)
