#pragma once

#include "Renderer.h"

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

    int width() const override { return m_size.width; }
    int height() const override { return m_size.height; }

    virtual void surfaceCreated(ANativeWindow*) override;
    virtual void surfaceChanged(int format, unsigned width, unsigned height) override;
    virtual void surfaceRedrawNeeded() override;
    virtual void surfaceDestroyed() override;

    virtual void handleExportedBuffer(const std::shared_ptr<ExportedBuffer>&) override;

private:
    struct TransactionContext;
    void scheduleFrame(TransactionContext*);
    void finishFrame(const std::shared_ptr<ExportedBuffer>&);

    static void onTransactionCompleteOnAnyThread(void* data, ASurfaceTransactionStats* stats);

    Page& m_page;
    struct {
        ANativeWindow* window {nullptr};
        ASurfaceControl* control {nullptr};
    } m_surface;

    struct {
        unsigned width;
        unsigned height;
    } m_size;

    struct {
        bool dispatchFrameCompleteCallback {false};

        std::shared_ptr<ExportedBuffer> exportedBuffer;
        std::shared_ptr<ExportedBuffer> lockedBuffer;
    } m_state;
};

#endif
