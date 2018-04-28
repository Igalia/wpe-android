#include <wpe/wpe.h>

#include "interfaces.h"
#include "logging.h"
#include <cstring>

extern "C" {

__attribute__((visibility("default")))
struct wpe_loader_interface _wpe_loader_interface = {
    [] (const char* object_name) -> void* {
        ALOGV("_wpe_loader_interface queried for object_name %s", object_name);

        if (!std::strcmp(object_name, "_wpe_renderer_host_interface"))
            return &android_renderer_host_impl;

        if (!std::strcmp(object_name, "_wpe_renderer_backend_egl_interface"))
            return &android_renderer_backend_egl_impl;

        if (!std::strcmp(object_name, "_wpe_renderer_backend_egl_target_interface"))
            return &android_renderer_backend_egl_target_impl;

        if (!std::strcmp(object_name, "_wpe_renderer_backend_egl_offscreen_target_interface"))
            return &android_renderer_backend_egl_offscreen_target_impl;

        return nullptr;
    },
};

}
