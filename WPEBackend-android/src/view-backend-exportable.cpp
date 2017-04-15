#include <wpe-android/view-backend-exportable.h>

#include "ipc.h"
#include "ipc-android.h"
#include "logging.h"

namespace Exportable {

class ViewBackend;

struct ClientBundle {
    ViewBackend* viewBackend;
    uint32_t width;
    uint32_t height;
};

class ViewBackend : public IPC::Host::Handler {
public:
    ViewBackend(ClientBundle*, struct wpe_view_backend*);
    virtual ~ViewBackend();

    IPC::Host& ipcHost() { return m_ipcHost; }

    void initialize();
    void frameComplete();

private:
    // IPC::Host::Handler
    void handleMessage(char*, size_t) override;

    ClientBundle* m_clientBundle;
    struct wpe_view_backend* m_backend;

    IPC::Host m_ipcHost;
};

ViewBackend::ViewBackend(ClientBundle* clientBundle, struct wpe_view_backend* backend)
    : m_clientBundle(clientBundle)
    , m_backend(backend)
{
    m_clientBundle->viewBackend = this;
    m_ipcHost.initialize(*this);
}

ViewBackend::~ViewBackend()
{
    m_ipcHost.deinitialize();
    m_clientBundle->viewBackend = nullptr;
    m_backend = nullptr;
    m_clientBundle = nullptr;
}

void ViewBackend::initialize()
{
    wpe_view_backend_dispatch_set_size(m_backend, m_clientBundle->width, m_clientBundle->height);
}

void ViewBackend::frameComplete()
{
    IPC::Message message;
    IPC::FrameComplete::construct(message);
    m_ipcHost.sendMessage(IPC::Message::data(message), IPC::Message::size);
}

void ViewBackend::handleMessage(char*, size_t)
{
}

} // namespace Exportable

extern "C" {

struct wpe_android_view_backend_exportable {
    Exportable::ClientBundle* clientBundle;
    struct wpe_view_backend* backend;
};

struct wpe_view_backend_interface android_view_backend_exportable_impl = {
    // create
    [] (void* data, struct wpe_view_backend* backend) -> void*
    {
        auto* clientBundle = static_cast<Exportable::ClientBundle*>(data);
        return new Exportable::ViewBackend(clientBundle, backend);
    },
    // destroy
    [] (void* data)
    {
        auto* backend = static_cast<Exportable::ViewBackend*>(data);
        delete backend;
    },
    // initialize
    [] (void* data)
    {
        ALOGV("noop_view_backend_impl::initialize()");
        auto& backend = *static_cast<Exportable::ViewBackend*>(data);
        backend.initialize();
    },
    // get_renderer_host_fd
    [] (void* data) -> int
    {
        auto& backend = *static_cast<Exportable::ViewBackend*>(data);
        return backend.ipcHost().releaseClientFD();
    },
};

__attribute__((visibility("default")))
struct wpe_android_view_backend_exportable*
wpe_android_view_backend_exportable_create(uint32_t width, uint32_t height)
{
    auto* clientBundle = new Exportable::ClientBundle{ nullptr, width, height };
    struct wpe_view_backend* backend = wpe_view_backend_create_with_backend_interface(&android_view_backend_exportable_impl, clientBundle);

    auto* exportable = new struct wpe_android_view_backend_exportable;
    exportable->clientBundle = clientBundle;
    exportable->backend = backend;

    return exportable;
}

__attribute__((visibility("default")))
void
wpe_android_view_backend_exportable_destroy(struct wpe_android_view_backend_exportable* exportable)
{
    wpe_view_backend_destroy(exportable->backend);
    delete exportable->clientBundle;
    delete exportable;
}

__attribute__((visibility("default")))
struct wpe_view_backend*
wpe_android_view_backend_exportable_get_view_backend(struct wpe_android_view_backend_exportable* exportable)
{
    return exportable->backend;
}

__attribute__((visibility("default")))
void
wpe_android_view_backend_exportable_dispatch_frame_complete(struct wpe_android_view_backend_exportable* exportable)
{
    exportable->clientBundle->viewBackend->frameComplete();
}

}
