#include "interfaces.h"

#include "hacks.h"
#include "ipc.h"
#include "ipc-android.h"
#include "logging.h"
#include <EGL/egl.h>

class EGLTarget : public IPC::Client::Handler {
public:
    EGLTarget(struct wpe_renderer_backend_egl_target*, int);
    virtual ~EGLTarget();

    // IPC::Client::Handle
    void handleMessage(char*, size_t) override;

    struct wpe_renderer_backend_egl_target* target;

    IPC::Client ipcClient;
};

EGLTarget::EGLTarget(struct wpe_renderer_backend_egl_target* target, int hostFd)
    : target(target)
{
    ipcClient.initialize(*this, hostFd);
}

EGLTarget::~EGLTarget()
{
    ipcClient.deinitialize();
}

void EGLTarget::handleMessage(char* data, size_t size)
{
    if (size != IPC::Message::size)
        return;

    auto& message = IPC::Message::cast(data);
    switch (message.messageCode) {
    case IPC::FrameComplete::code:
    {
        wpe_renderer_backend_egl_target_dispatch_frame_complete(target);
        break;
    }
    default:
        ALOGV("EGLTarget: invalid message");
        break;
    }
}

struct wpe_renderer_backend_egl_interface android_renderer_backend_egl_impl = {
    // create
    [] (int) -> void*
    {
        ALOGV("noop_renderer_backend_egl_impl::create()");
        return nullptr;
    },
    // destroy
    [] (void*)
    {
        ALOGV("noop_renderer_backend_egl_impl::destroy()");
    },
    // get_native_display
    [] (void*) -> EGLNativeDisplayType
    {
        ALOGV("noop_renderer_backend_egl_impl::get_native_display()");
        return EGL_DEFAULT_DISPLAY;
    },
};

struct wpe_renderer_backend_egl_target_interface android_renderer_backend_egl_target_impl = {
    // create
    [] (struct wpe_renderer_backend_egl_target* target, int hostFd) -> void*
    {
        ALOGV("noop_renderer_backend_egl_target_impl::create() fd %d", hostFd);
        return new EGLTarget(target, hostFd);
    },
    // destroy
    [] (void* data)
    {
        ALOGV("noop_renderer_backend_egl_target_impl::destroy()");
        auto* target = static_cast<EGLTarget*>(data);
        delete target;
    },
    // initialize
    [] (void*, void*, uint32_t width, uint32_t height)
    {
        ALOGV("noop_renderer_backend_egl_target_impl::initialize() (%u,%u)", width, height);
    },
    // get_native_window
    [] (void*) -> EGLNativeWindowType
    {
        ALOGV("noop_renderer_backend_egl_target_impl::get_native_window()");
        ALOGV("  s_providedWindow %p\n", s_hack_providedWindow);
        return s_hack_providedWindow;
    },
    // resize
    [] (void*, uint32_t, uint32_t) { },
    // frame_will_render
    [] (void*)
    {
        // ALOGV("noop_renderer_backend_egl_target_impl::frame_will_render()");
    },
    // frame_rendered
    [] (void*)
    {
        // ALOGV("noop_renderer_backend_egl_target_impl::frame_rendered()");
    },
};

struct wpe_renderer_backend_egl_offscreen_target_interface android_renderer_backend_egl_offscreen_target_impl = {
    // create
    [] () -> void*
    {
        ALOGV("noop_renderer_backend_egl_offscreen_target_impl::create()");
        return nullptr;
    },
    // destroy
    [] (void*)
    {
        ALOGV("noop_renderer_backend_egl_offscreen_target_impl::destroy()");
    },
    // initialize
    [] (void*, void*)
    {
        ALOGV("noop_renderer_backend_egl_offscreen_target_impl::initialize()");
    },
    // get_native_window
    [] (void*) -> EGLNativeWindowType
    {
        ALOGV("noop_renderer_backend_egl_offscreen_target_impl::get_native_window()");
        return nullptr;
    },
};
