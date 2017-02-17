#include <wpe/loader.h>

#include "logging.h"
#include <cstring>
#include <wpe/renderer-host.h>
#include <wpe/view-backend.h>

extern "C" {

struct wpe_renderer_host_interface noop_renderer_host_impl = {
    // create
    [] () -> void* { return nullptr; },
    // destroy
    [] (void*) { },
    // create_client
    [] (void*) -> int { return -1; },
};

struct wpe_view_backend_interface noop_view_backend_impl = {
    // create
    [] (void*, struct wpe_view_backend*) -> void* { return nullptr; },
    // destroy
    [] (void*) { },
    // initialize
    [] (void*) { },
    // get_renderer_host_fd
    [] (void*) -> int { return -1; },
};

__attribute__((visibility("default")))
struct wpe_loader_interface _wpe_loader_interface = {
    [] (const char* object_name) -> void* {
        ALOGV("wpe-android: _wpe_loader_interface for object_name %s", object_name);

        if (!std::strcmp(object_name, "_wpe_renderer_host_interface"))
            return &noop_renderer_host_impl;

        if (!std::strcmp(object_name, "_wpe_view_backend_interface"))
            return &noop_view_backend_impl;

        return nullptr;
    },
};

}
