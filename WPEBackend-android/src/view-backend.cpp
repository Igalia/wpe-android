#include "view-backend.h"

#include "hacks.h"
#include "interfaces.h"
#include "ipc-android.h"
#include "logging.h"

ViewBackend::ViewBackend(struct wpe_view_backend* backend)
    : backend(backend)
{
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

struct wpe_view_backend_interface android_view_backend_impl = {
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
