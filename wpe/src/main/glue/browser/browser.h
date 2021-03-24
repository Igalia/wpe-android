#pragma once

#include <functional>

#include <wpe/webkit.h>
#include <wpe/wpe.h>

extern void wpe_browser_glue_init();
extern void wpe_browser_glue_deinit();
extern void wpe_browser_glue_new_web_view(jint, jint, std::function<void (jlong)>);
extern void wpe_browser_glue_close_web_view(jlong);
extern void wpe_browser_glue_load_url(jlong, const char*, jsize);
extern void wpe_browser_glue_go_back(jlong);
extern void wpe_browser_glue_go_forward(jlong);
extern void wpe_browser_glue_reload(jlong);
extern void wpe_browser_glue_frame_complete();
extern void wpe_browser_glue_touch_event(jlong, jint, jfloat, jfloat);