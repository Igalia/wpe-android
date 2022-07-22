#include "LooperThread.h"

#include "Logging.h"

#include <android/looper.h>

static LooperThread* s_looperThread = nullptr;

LooperThread::~LooperThread() { ALooper_release(m_looper); }

void LooperThread::initialize()
{
    ALOGV("LooperThread::initialize() ALooper %p", ALooper_forThread());
    s_looperThread = new LooperThread;
    s_looperThread->m_looper = ALooper_forThread();
    ALooper_acquire(s_looperThread->m_looper);
}

LooperThread& LooperThread::instance() { return *s_looperThread; }
