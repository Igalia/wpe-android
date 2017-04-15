#pragma once

extern void wpe_uiprocess_glue_init(JNIEnv*, jobject, jint, jint);
extern void wpe_uiprocess_glue_deinit();

extern void wpe_uiprocess_glue_frame_complete();

extern void wpe_uiprocess_glue_touch_event(jlong, jint, jfloat, jfloat);