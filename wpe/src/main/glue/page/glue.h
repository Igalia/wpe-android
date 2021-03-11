#pragma once

extern void wpe_page_glue_init(jint, jint);
extern void wpe_page_glue_deinit();
extern void wpe_page_glue_set_page_url(const char*, jsize);
extern void wpe_page_glue_frame_complete();
extern void wpe_page_glue_touch_event(jlong, jint, jfloat, jfloat);