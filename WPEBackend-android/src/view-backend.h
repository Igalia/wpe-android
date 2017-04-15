#pragma once

#include "ipc.h"

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
