#include "interfaces.h"

struct wpe_renderer_host_interface android_renderer_host_impl = {
    // create
    [] () -> void* { return nullptr; },
    // destroy
    [] (void*) { },
    // create_client
    [] (void*) -> int { return -1; },
};
