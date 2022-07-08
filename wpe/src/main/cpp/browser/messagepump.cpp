#include "messagepump.h"

#include "logging.h"

#include <algorithm>
#include <android/looper.h>
#include <sys/eventfd.h>
#include <unistd.h>

namespace {
int looperCollectEventsCallback(int fd, int events, void* data) {
    MessagePump* pump = reinterpret_cast<MessagePump*>(data);
    pump->onCollectEventsLooperCallback(fd, events);
    return 1;  // continue listening for events
}

int looperDisptachCallback(int fd, int events, void* data) {
    MessagePump* pump = reinterpret_cast<MessagePump*>(data);
    pump->onDispatchLooperCallback();
    return 1;  // continue listening for events
}

int glibEventsToLooperEvents(gushort events) {
    int looperEvents = 0;
    if (events & G_IO_IN)
        looperEvents |= ALOOPER_EVENT_INPUT;
    if (events & G_IO_OUT)
        looperEvents |= ALOOPER_EVENT_OUTPUT;
    if (events & G_IO_ERR)
        looperEvents |= ALOOPER_EVENT_ERROR;
    if (events & G_IO_HUP)
        looperEvents |= ALOOPER_EVENT_HANGUP;
    if (events & G_IO_NVAL)
        looperEvents |= ALOOPER_EVENT_INVALID;
    return looperEvents;
}

gushort looperEventsToGLibEvents(int events) {
    gushort glibEvents = 0;
    if (events & ALOOPER_EVENT_INPUT)
        glibEvents |= G_IO_IN;
    if (events & ALOOPER_EVENT_OUTPUT)
        glibEvents |= G_IO_OUT;
    if (events & ALOOPER_EVENT_ERROR)
        glibEvents |= G_IO_ERR;
    if (events & ALOOPER_EVENT_HANGUP)
        glibEvents |= G_IO_HUP;
    if (events & ALOOPER_EVENT_INVALID)
        glibEvents |= G_IO_NVAL;
    return glibEvents;
}
}

/*
 * Message pump implements integration between the GLib main loop
 * and the Android native ALooper run loop and event handling..
 *
 * Message pump "pumps" event and message fds from GLib context and pushes
 * them to Android looper for polling. When event occurs, message pump receives
 * callback from Android looper and initiates GLib main loop stages
 * (prepare, check, dispatch) and makes the appropriate calls into GLib.
 * This allows running WPE UI on Android main UI thread
 */

MessagePump::MessagePump()
{
    // The Android native ALooper uses epoll to poll file descriptors.
    // We use eventfd to signal that GLib dispatch stages can be started.
    m_dispatchFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);

    m_looper = ALooper_prepare(0);
    // Add a reference to the looper so it isn't deleted on us.
    ALooper_acquire(m_looper);
    ALooper_addFd(m_looper, m_dispatchFd, 0, ALOOPER_EVENT_INPUT, &looperDisptachCallback, reinterpret_cast<void*>(this));

    GMainContext *context = g_main_context_default();
    g_main_context_acquire(context);
    m_context = g_main_context_ref(context);
    prepare();
}

MessagePump::~MessagePump()
{
    std::vector<LooperGLibPollFd>::iterator it=m_looperGlibPollFds.begin();
    while (it!=m_looperGlibPollFds.end()) {
        ALooper_removeFd(m_looper, it->fd);
        it = m_looperGlibPollFds.erase(it);
    }

    g_free(m_pollFds);
    m_pollFdsSize = 0;

    /* Release GMainContext loop */
    g_main_context_unref(m_context);

    ALooper_release(m_looper);
    m_looper = nullptr;

    close(m_dispatchFd);
}

void MessagePump::quit()
{
    // Clear the eventfd and reset its counter to 0
    int64_t value;
    read(m_dispatchFd, &value, sizeof(value));

    dispatch();
}

void MessagePump::invoke(void (* callback)(void*), void* callbackData, void (* destroy)(void*))
{
    struct GenericCallback
    {
        void (* callback)(void*);
        void* callbackData;
        void (* destroy)(void*);
    };

    auto* data = new GenericCallback { callback, callbackData, destroy };
    g_main_context_invoke_full(m_context, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* genericData = reinterpret_cast<GenericCallback*>(data);
        genericData->callback(genericData->callbackData);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        auto* genericData = reinterpret_cast<GenericCallback*>(data);
        if (genericData->destroy)
            genericData->destroy(genericData->callbackData);
        delete genericData;
    });
}

void MessagePump::prepare() {
    g_main_context_prepare (m_context, &m_maxPriority);

    if (m_pollFds == nullptr) {
        m_pollFdsSize = 1; // There will be at least one fd in GMainContext
        m_pollFds = g_new (GPollFD, m_pollFdsSize);
    }

    gint nfds;
    gint timeout = 0;
    while ((nfds = g_main_context_query (m_context, m_maxPriority, &timeout,
                                         m_pollFds,
                                         m_pollFdsSize)) > m_pollFdsSize) {
        g_free(m_pollFds);
        m_pollFdsSize = nfds;
        m_pollFds = g_new (GPollFD, nfds);
    }

    char *glibPollFdAddedToLooperFlags = (char *)g_new0(char, nfds);

    // Update reference count of each looper glib poll fd
    for (auto& pollFd : m_looperGlibPollFds) {
        pollFd.ref = 0;

        for (int i=0; i < m_pollFdsSize; i++) {
            GPollFD* pfd = m_pollFds+i;

            if (pollFd.fd == pfd->fd) {
                *(glibPollFdAddedToLooperFlags+i) = 1;
                pollFd.pfd = pfd;
                pollFd.ref = 1;
                pfd->revents = 0;
                break;
            }
        }
    }

    // Add new fds to ALooper if they weren't added before
    for (int i=0; i < m_pollFdsSize; i++) {
        GPollFD *pfd = m_pollFds + i;
        gint exists = (gint) *(glibPollFdAddedToLooperFlags + i);

        if (exists)
            continue;

        pfd->revents = 0;

        // ALooper uses epoll for polling and in order to dispatching work correctly it requires ALOOPER_EVENT_OUTPUT
        // but G_IO_OUT is not flagged by default glib wakeup fd nor by wpe android backend ipc sockets.
        ALooper_addFd(m_looper, pfd->fd, 0, glibEventsToLooperEvents( pfd->events)|ALOOPER_EVENT_OUTPUT,
                          &looperCollectEventsCallback, reinterpret_cast<void*>(this));

        m_looperGlibPollFds.push_back({ pfd->fd, pfd, 1 });
    }

    g_free(glibPollFdAddedToLooperFlags);

    // Remove looper glib poll fds which aren't required anymore
    std::vector<LooperGLibPollFd>::iterator it=m_looperGlibPollFds.begin();
    while (it!=m_looperGlibPollFds.end()) {
        if (it->ref == 0) {
            ALooper_removeFd(m_looper, it->fd);
            it = m_looperGlibPollFds.erase(it);
        } else {
            ++it;
        }
    }
}

void MessagePump::dispatch()
{
    if (g_main_context_check(m_context, m_maxPriority, m_pollFds, m_pollFdsSize)) {
        g_main_context_dispatch(m_context);
    }
}

void MessagePump::onCollectEventsLooperCallback(int fd, int events) {
    for (int i=0; i < m_pollFdsSize; i++) {
        if (m_pollFds[i].fd == fd) {
            m_pollFds[i].revents = looperEventsToGLibEvents(events);
        }
    }

    // Schedule dispatch callback
    uint64_t value = 1;
    write(m_dispatchFd, &value, sizeof(value));
}

void MessagePump::onDispatchLooperCallback() {
    // Clear the eventfd and reset its counter to 0
    uint64_t value;
    read(m_dispatchFd, &value, sizeof(value));

    dispatch();
    prepare ();
}

