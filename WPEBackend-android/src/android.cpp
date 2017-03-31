#include <wpe/loader.h>

#include "ipc.h"
#include "logging.h"
#include <EGL/egl.h>
#include <cstring>
#include <wpe/renderer-backend-egl.h>
#include <wpe/renderer-host.h>
#include <wpe/view-backend.h>

#include <android/native_window.h>

static ANativeWindow* s_providedWindow = nullptr;

struct wpe_renderer_host_interface noop_renderer_host_impl = {
    // create
    [] () -> void* { return nullptr; },
    // destroy
    [] (void*) { },
    // create_client
    [] (void*) -> int { return -1; },
};

namespace IPC {
struct FrameComplete {
    uint8_t padding[24];

    static const uint64_t code = 23;
    static void construct(Message& message)
    {
        message.messageCode = code;
    }
};
static_assert(sizeof(FrameComplete) == Message::dataSize, "FrameComplete is of correct size");
}

class ViewBackend : public IPC::Host::Handler {
public:
    ViewBackend(struct wpe_view_backend*);
    virtual ~ViewBackend();

    // IPC::Host::Handler
    void handleMessage(char*, size_t) override;

    void frameComplete();

    struct wpe_view_backend* backend;

    IPC::Host ipcHost;

    GMainContext* mainContext;
};

static ViewBackend* s_hack_viewBackend = nullptr;

ViewBackend::ViewBackend(struct wpe_view_backend* backend)
    : backend(backend)
{
    s_hack_viewBackend = this;

    ipcHost.initialize(*this);

    mainContext = g_main_context_get_thread_default();
}

ViewBackend::~ViewBackend()
{
    ipcHost.deinitialize();
}

void ViewBackend::handleMessage(char*, size_t)
{
}

void ViewBackend::frameComplete()
{
    GSource* source = g_idle_source_new();
    g_source_set_callback(source,
        [](gpointer data) -> gboolean {
            ALOGV("ViewBackend::frameComplete()::lambda()");
            auto& backend = *static_cast<ViewBackend*>(data);

            IPC::Message message;
            IPC::FrameComplete::construct(message);
            backend.ipcHost.sendMessage(IPC::Message::data(message), IPC::Message::size);
            return FALSE;
        }, this, nullptr);
    g_source_attach(source, mainContext);
    g_source_unref(source);
}

struct wpe_view_backend_interface noop_view_backend_impl = {
    // create
    [] (void*, struct wpe_view_backend* backend) -> void*
    {
        return new ViewBackend(backend);
    },
    // destroy
    [] (void* data)
    {
        auto* backend = static_cast<ViewBackend*>(data);
        delete backend;
    },
    // initialize
    [] (void* data)
    {
        ALOGV("noop_view_backend_impl::initialize()");
        auto& backend = *static_cast<ViewBackend*>(data);
        wpe_view_backend_dispatch_set_size(backend.backend, 1920, 1080);
    },
    // get_renderer_host_fd
    [] (void* data) -> int
    {
        auto& backend = *static_cast<ViewBackend*>(data);
        return backend.ipcHost.releaseClientFD();
    },
};

struct wpe_renderer_backend_egl_interface noop_renderer_backend_egl_impl = {
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
        ALOGV("EGLTarget: FrameComplete");
        wpe_renderer_backend_egl_target_dispatch_frame_complete(target);
        break;
    }
    default:
        ALOGV("EGLTarget: invalid message");
        break;
    }
}

struct wpe_renderer_backend_egl_target_interface noop_renderer_backend_egl_target_impl = {
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
        ALOGV("  s_providedWindow %p\n", s_providedWindow);
        return s_providedWindow;
    },
    // resize
    [] (void*, uint32_t, uint32_t) { },
    // frame_will_render
    [] (void*)
    {
        ALOGV("noop_renderer_backend_egl_target_impl::frame_will_render()");
    },
    // frame_rendered
    [] (void*)
    {
        ALOGV("noop_renderer_backend_egl_target_impl::frame_rendered()");
    },
};

struct wpe_renderer_backend_egl_offscreen_target_interface noop_renderer_backend_egl_offscreen_target_impl = {
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

extern "C" {

__attribute__((visibility("default")))
struct wpe_loader_interface _wpe_loader_interface = {
    [] (const char* object_name) -> void* {
        ALOGV("_wpe_loader_interface queried for object_name %s", object_name);

        if (!std::strcmp(object_name, "_wpe_renderer_host_interface"))
            return &noop_renderer_host_impl;

        if (!std::strcmp(object_name, "_wpe_view_backend_interface"))
            return &noop_view_backend_impl;

        if (!std::strcmp(object_name, "_wpe_renderer_backend_egl_interface"))
            return &noop_renderer_backend_egl_impl;

        if (!std::strcmp(object_name, "_wpe_renderer_backend_egl_target_interface"))
            return &noop_renderer_backend_egl_target_impl;

        if (!std::strcmp(object_name, "_wpe_renderer_backend_egl_offscreen_target_interface"))
            return &noop_renderer_backend_egl_offscreen_target_impl;

        return nullptr;
    },
};

__attribute__((visibility("default")))
void libwpe_android_provideNativeWindow(ANativeWindow* nativeWindow)
{
    ALOGV("libwpe_android_provideNativeWindow() nativeWindow %p size (%d,%d)",
        nativeWindow,
        ANativeWindow_getWidth(nativeWindow), ANativeWindow_getHeight(nativeWindow));
    s_providedWindow = nativeWindow;
}

__attribute__((visibility("default")))
void libwpe_android_hack_frameComplete()
{
    ALOGV("libwpe_android_frameComplete() s_hack_viewBackend %p", s_hack_viewBackend);
    s_hack_viewBackend->frameComplete();
}

}
