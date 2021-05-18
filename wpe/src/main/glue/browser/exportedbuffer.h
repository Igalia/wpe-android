#pragma once

#include <android/hardware_buffer.h>
#include <memory>
#include <stdint.h>

struct ExportedBuffer : public std::enable_shared_from_this<ExportedBuffer> {
    AHardwareBuffer* buffer;

    uint32_t poolID;
    uint32_t bufferID;

    struct {
        uint32_t width;
        uint32_t height;
    } size;

    ExportedBuffer(AHardwareBuffer* buffer, uint32_t poolID, uint32_t bufferID)
        : buffer(buffer)
        , poolID(poolID)
        , bufferID(bufferID)
        , size({ 0, 0 })
    {
        if (buffer) {
            AHardwareBuffer_acquire(buffer);

            AHardwareBuffer_Desc desc;
            AHardwareBuffer_describe(buffer, &desc);
            size = { desc.width, desc.height };
        }
    }

    ExportedBuffer(ExportedBuffer&&) = delete;
    ExportedBuffer& operator=(ExportedBuffer&&) = delete;
    ExportedBuffer(const ExportedBuffer&) = delete;
    ExportedBuffer& operator=(const ExportedBuffer&) = delete;

    ~ExportedBuffer()
    {
        if (buffer)
            AHardwareBuffer_release(buffer);
    }
};
