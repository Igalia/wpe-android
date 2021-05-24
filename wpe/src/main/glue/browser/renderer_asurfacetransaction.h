#pragma once

#include "renderer.h"
#include <android/api-level.h>

#if __ANDROID_API__ >= 29

struct ANativeWindow;
struct ASurfaceControl;
struct ASurfaceTransactionStats;

class Page;

class RendererASurfaceTransaction final : public Renderer {
public:
    RendererASurfaceTransaction(Page&, unsigned width, unsigned height);
    virtual ~RendererASurfaceTransaction();

    virtual void surfaceCreated(ANativeWindow*) override;
    virtual void surfaceChanged(int format, unsigned width, unsigned height) override;
    virtual void surfaceRedrawNeeded() override;
    virtual void surfaceDestroyed() override;

    virtual void handleExportedBuffer(const std::shared_ptr<ExportedBuffer>&) override;

private:
    struct TransactionContext;
    void scheduleFrame(TransactionContext*);
    void finishFrame(const std::shared_ptr<ExportedBuffer>&, bool dispatchFrameCompleteCallback);

    static void onTransactionComplete(void* data, ASurfaceTransactionStats* stats);

    Page& m_page;
    struct {
        ANativeWindow* window { nullptr };
        ASurfaceControl* control { nullptr };
    } m_surface;

    struct {
        unsigned width;
        unsigned height;
    } m_size;

    std::shared_ptr<ExportedBuffer> m_lockedBuffer;
};

#endif