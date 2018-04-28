#ifndef wpebackend_android_view_backend_exportable_h
#define wpebackend_android_view_backend_exportable_h

#ifdef __cplusplus
extern "C" {
#endif

#include <wpe/wpe.h>

struct wpe_android_view_backend_exportable;

struct wpe_android_view_backend_exportable*
wpe_android_view_backend_exportable_create(uint32_t width, uint32_t height);

void
wpe_android_view_backend_exportable_destroy(struct wpe_android_view_backend_exportable*);

struct wpe_view_backend*
wpe_android_view_backend_exportable_get_view_backend(struct wpe_android_view_backend_exportable*);

void
wpe_android_view_backend_exportable_dispatch_frame_complete(struct wpe_android_view_backend_exportable*);

#ifdef __cplusplus
}
#endif

#endif // wpebackend_android_view_backend_exportable_h
