#pragma once

#include <android/log.h>

#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "WPEDemo-Native", __VA_ARGS__)
