#pragma once

#include <glib.h>
#include <memory>
#include <vector>

struct ALooper;

class MessagePump {
public:
    MessagePump();

    MessagePump(MessagePump&&) = delete;
    MessagePump& operator=(MessagePump&&) = delete;
    MessagePump(const MessagePump&) = delete;
    MessagePump& operator=(const MessagePump&) = delete;

    ~MessagePump();

    void quit();

    void invoke(void (*callback)(void*), void* callbackData, void (*destroy)(void*));

    void onCollectEventsLooperCallback(int fd, int events);
    void onDispatchLooperCallback();

private:
    void prepare();
    void dispatch();

    ALooper* m_looper = nullptr;
    int m_dispatchFd = 0;

    GMainContext* m_context = nullptr;
    gint m_maxPriority = 0;
    GPollFD* m_pollFds = nullptr;
    gint m_pollFdsSize = 1;

    struct LooperGLibPollFd {
        int fd;
        GPollFD* pfd;
        int ref;
    };

    std::vector<LooperGLibPollFd> m_looperGlibPollFds;
};
