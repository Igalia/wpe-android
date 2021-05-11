#pragma once

struct ALooper;

class LooperThread {
public:
    static void initialize();
    static LooperThread& instance();

    ~LooperThread();

    ALooper* looper() const { return m_looper; }

private:
    ALooper* m_looper { nullptr };
};