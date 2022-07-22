#pragma once

#include "ExportedBuffer.h"

struct ANativeWindow;

class Renderer {
public:
    virtual ~Renderer() = default;

    virtual int width() const = 0;
    virtual int height() const = 0;

    virtual void surfaceCreated(ANativeWindow*) = 0;
    virtual void surfaceChanged(int format, unsigned width, unsigned height) = 0;
    virtual void surfaceRedrawNeeded() = 0;
    virtual void surfaceDestroyed() = 0;

    virtual void handleExportedBuffer(const std::shared_ptr<ExportedBuffer>&) = 0;
};
