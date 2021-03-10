#pragma once

#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "WPE Glue", __VA_ARGS__)

static int pfd[2];
static pthread_t thr;
static const char *tag = "wpe-android";

static void *thread_func(void*)
{
    ssize_t rdsz;
    char buf[128];
    while((rdsz = read(pfd[0], buf, sizeof buf - 1)) > 0) {
        if(buf[rdsz - 1] == '\n') --rdsz;
        buf[rdsz] = 0;
        __android_log_write(ANDROID_LOG_DEBUG, tag, buf);
    }
    return 0;
}

static int pipe_stdout_to_logcat()
{
    // Make stdout line-buffered and stderr unbuffered
    setvbuf(stdout, 0, _IOLBF, 0);
    setvbuf(stderr, 0, _IONBF, 0);

    // Create the pipe and redirect stdout and stderr
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    // Spawn the logging thread
    if(pthread_create(&thr, 0, thread_func, 0) == -1)
        return -1;
    pthread_detach(thr);
    return 0;
}

static void enable_gst_debug()
{
    setenv("GST_DEBUG", "3", 1);
}

