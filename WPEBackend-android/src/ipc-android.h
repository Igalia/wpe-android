#pragma once

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
