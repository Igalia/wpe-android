#include "renderer_asurfacetransaction.h"

#if __ANDROID_API__ >= 29

#include "exportedbuffer.h"
#include "logging.h"
#include "browser.h"
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <wpe-android/view-backend-exportable.h>

struct RendererASurfaceTransaction::TransactionContext {
    RendererASurfaceTransaction& renderer;
    std::shared_ptr<ExportedBuffer> buffer;
    bool dispatchFrameCompleteCallback;
};

RendererASurfaceTransaction::RendererASurfaceTransaction(Page& page, unsigned width, unsigned height)
    : m_page(page)
    , m_size({ width, height })
{
    ALOGV("RendererASurfaceTransaction() page %p", &m_page);
}

RendererASurfaceTransaction::~RendererASurfaceTransaction()
{
    if (m_surface.control)
        ASurfaceControl_release(m_surface.control);
    if (m_surface.window)
        ANativeWindow_release(m_surface.window);

    m_lockedBuffer = nullptr;
}

void RendererASurfaceTransaction::surfaceCreated(ANativeWindow* window)
{
    m_surface.window = window;
    m_surface.control = ASurfaceControl_createFromWindow(m_surface.window, "RendererASurfaceTransaction");
}

void RendererASurfaceTransaction::surfaceChanged(int format, unsigned width, unsigned height)
{
    m_size.width = width;
    m_size.height = height;
}

void RendererASurfaceTransaction::surfaceRedrawNeeded()
{
    if (!m_surface.control)
        return;

    if (!m_lockedBuffer)
        return;
    if (m_lockedBuffer->size.width != m_size.width || m_lockedBuffer->size.height != m_size.height)
        return;

    auto exportedBuffer = std::exchange(m_lockedBuffer, nullptr);
    scheduleFrame(new TransactionContext { *this, exportedBuffer, false });
}

void RendererASurfaceTransaction::surfaceDestroyed()
{
    ASurfaceControl_release(m_surface.control);
    m_surface.control = nullptr;

    ANativeWindow_release(m_surface.window);
    m_surface.window = nullptr;
}

void RendererASurfaceTransaction::handleExportedBuffer(const std::shared_ptr<ExportedBuffer>& exportedBuffer)
{
    if (!m_surface.control)
        return;

    ALOGV("RendererASurfaceTransaction::handleExportedBuffer()");
    scheduleFrame(new TransactionContext { *this, exportedBuffer, true });
}

void RendererASurfaceTransaction::scheduleFrame(TransactionContext* transactionContext)
{
    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setVisibility(transaction, m_surface.control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    ASurfaceTransaction_setZOrder(transaction, m_surface.control, 0);

    ASurfaceTransaction_setBuffer(transaction, m_surface.control, transactionContext->buffer->buffer, -1);

    ASurfaceTransaction_setOnComplete(transaction, transactionContext, onTransactionComplete);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

void RendererASurfaceTransaction::finishFrame(const std::shared_ptr<ExportedBuffer>& exportedBuffer, bool dispatchFrameCompleteCallback)
{
    ALOGV("RendererASurfaceTransaction::finishFrame()");

    if (m_lockedBuffer) {
        wpe_android_view_backend_exportable_dispatch_release_buffer(m_page.exportable(),
                                                                    m_lockedBuffer->buffer,
                                                                    m_lockedBuffer->poolID,
                                                                    m_lockedBuffer->bufferID);
        m_lockedBuffer = nullptr;
    }
    m_lockedBuffer = exportedBuffer;

    if (dispatchFrameCompleteCallback)
        wpe_android_view_backend_exportable_dispatch_frame_complete(m_page.exportable());
}

void RendererASurfaceTransaction::onTransactionComplete(void* data, ASurfaceTransactionStats* stats)
{
    ALOGV("RendererASurfaceTransaction::onTransactionComplete() context %p", data);
    auto* context = static_cast<TransactionContext*>(data);

    Browser::getInstance().invoke(
            [](void* data) {
                auto* context = static_cast<TransactionContext*>(data);
                context->renderer.finishFrame(context->buffer, context->dispatchFrameCompleteCallback);
            }, context,
            [](void* data) {
                delete static_cast<TransactionContext*>(data);
            });
}

#endif