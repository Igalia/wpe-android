/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "RendererASurfaceTransaction.h"

#include "Browser.h"
#include "Logging.h"

#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <wpe-android/view-backend-exportable.h>

struct RendererASurfaceTransaction::TransactionContext {
    RendererASurfaceTransaction& renderer;
    std::shared_ptr<ExportedBuffer> buffer;
};

static void releaseBuffer(struct wpe_android_view_backend_exportable* exportable, const ExportedBuffer& buffer)
{
    wpe_android_view_backend_exportable_dispatch_release_buffer(
        exportable, buffer.buffer, buffer.poolID, buffer.bufferID);
}

RendererASurfaceTransaction::RendererASurfaceTransaction(Page& page, unsigned width, unsigned height)
    : m_page(page)
    , m_size({width, height})
{
    ALOGV("RendererASurfaceTransaction() page %p", &m_page);
}

RendererASurfaceTransaction::~RendererASurfaceTransaction()
{
    if (m_surface.control)
        ASurfaceControl_release(m_surface.control);
    if (m_surface.window)
        ANativeWindow_release(m_surface.window);

    // Release the stored exported buffer, if any, and if different from the locked buffer.
    // If the same, the buffer will be released via the locked buffer.
    if (m_state.exportedBuffer && m_state.exportedBuffer != m_state.lockedBuffer)
        releaseBuffer(m_page.exportable(), *m_state.exportedBuffer);
    // If locked buffer still exists, release it.
    if (m_state.lockedBuffer)
        releaseBuffer(m_page.exportable(), *m_state.lockedBuffer);
    m_state = {};
}

void RendererASurfaceTransaction::surfaceCreated(ANativeWindow* window)
{
    // This is now the surface we work with. We also spawn the corresponding ASurfaceControl.
    m_surface.window = window;
    m_surface.control = ASurfaceControl_createFromWindow(m_surface.window, "RendererASurfaceTransaction");
}

void RendererASurfaceTransaction::surfaceChanged(int format, unsigned width, unsigned height)
{
    // Update the size.
    m_size.width = width;
    m_size.height = height;
}

void RendererASurfaceTransaction::surfaceRedrawNeeded()
{
    // Nothing is doable if there's no ASurfaceControl.
    if (!m_surface.control)
        return;

    // Redraw is needed -- if there's currently an exported buffer present, reuse it.
    if (m_state.exportedBuffer)
        scheduleFrame(new TransactionContext {*this, m_state.exportedBuffer});
}

void RendererASurfaceTransaction::surfaceDestroyed()
{
    // Regular cleanup of the ASurfaceControl as well as the ANativeWindow.

    ASurfaceControl_release(m_surface.control);
    m_surface.control = nullptr;

    ANativeWindow_release(m_surface.window);
    m_surface.window = nullptr;
}

void RendererASurfaceTransaction::handleExportedBuffer(const std::shared_ptr<ExportedBuffer>& exportedBuffer)
{
    // If there's an exported buffer being held that's different from the locked one, it has to
    // be released here since it won't be released otherwise.
    if (m_state.exportedBuffer && m_state.exportedBuffer != m_state.lockedBuffer)
        releaseBuffer(m_page.exportable(), *m_state.exportedBuffer);

    m_state.exportedBuffer = exportedBuffer;

    // Each buffer export requires a corresponding frame-complete callback. This is signalled here.
    m_state.dispatchFrameCompleteCallback = true;

    // Nothing is doable if there's no ASurfaceControl.
    if (!m_surface.control)
        return;

    // Finally, schedule the new frame, presenting the just-exported buffer.
    scheduleFrame(new TransactionContext {*this, m_state.exportedBuffer});
}

void RendererASurfaceTransaction::scheduleFrame(TransactionContext* transactionContext)
{
    // Take the TransactionContext and its buffer and form a transaction for it.
    // Upon transaction completion, the buffer will be presented on the surface.

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setVisibility(transaction, m_surface.control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    ASurfaceTransaction_setZOrder(transaction, m_surface.control, 0);

    ASurfaceTransaction_setBuffer(transaction, m_surface.control, transactionContext->buffer->buffer, -1);

    ASurfaceTransaction_setOnComplete(transaction, transactionContext, onTransactionCompleteOnAnyThread);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

void RendererASurfaceTransaction::finishFrame(const std::shared_ptr<ExportedBuffer>& exportedBuffer)
{
    ALOGV("RendererASurfaceTransaction::finishFrame() exportedBuffer %p tid: %d", exportedBuffer.get(), gettid());

    // If the current locked buffer is different from the current exported one, it should be released here.
    if (m_state.lockedBuffer && m_state.exportedBuffer != m_state.lockedBuffer)
        releaseBuffer(m_page.exportable(), *m_state.lockedBuffer);

    // The just-presented buffer is now also the locked one.
    m_state.lockedBuffer = exportedBuffer;

    // If the frame-complete callback dispatch was requested, it's invoked here.
    if (m_state.dispatchFrameCompleteCallback)
        wpe_android_view_backend_exportable_dispatch_frame_complete(m_page.exportable());
    m_state.dispatchFrameCompleteCallback = false;
}

// API documentation states that this callback can be dispatched on any thread.
void RendererASurfaceTransaction::onTransactionCompleteOnAnyThread(void* data, ASurfaceTransactionStats* stats)
{
    ALOGV("RendererASurfaceTransaction::onTransactionCompleteOnAnyThread() context %p tid: %d", data, gettid());

    // Relay the transaction completion to the webkit ui thread.
    Browser::instance().invokeOnUiThread(
        [](void* data) {
            auto* context = static_cast<TransactionContext*>(data);
            context->renderer.finishFrame(context->buffer);
        },
        data, [](void* data) { delete static_cast<TransactionContext*>(data); });
}
