// touchinject: replay finger trajectories through the real touchscreen node, so that the
// events take the same path as a finger (kernel timestamps, InputReader, InputConsumer
// resampling, Choreographer batching) instead of `input swipe`'s injected MotionEvents.
//
// The shell user can't write to the panel's own node (SELinux), so a uinput touchscreen with the
// same axes as the Pixel 7's fts_ts is created instead; InputReader treats it the same way.
//
// Usage: touchinject < trajectory
// Lines: "D <ms> <x> <y>" down, "M <ms> <x> <y>" move, "U <ms>" up; <ms> is relative to
// the start of the replay. Prints the CLOCK_MONOTONIC time of the start in ns.
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

static int fd;

static void emit(int type, int code, int value)
{
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) != sizeof(ev)) {
        perror("write");
        exit(1);
    }
}

static void absSetup(int code, int min, int max)
{
    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = code;
    abs.absinfo.minimum = min;
    abs.absinfo.maximum = max;
    ioctl(fd, UI_SET_ABSBIT, code);
    if (ioctl(fd, UI_ABS_SETUP, &abs) < 0) {
        perror("UI_ABS_SETUP");
        exit(1);
    }
}

int main(void)
{
    fd = open("/dev/uinput", O_WRONLY);
    if (fd < 0) {
        perror("open /dev/uinput");
        return 1;
    }
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    absSetup(ABS_MT_SLOT, 0, 9);
    absSetup(ABS_MT_TOUCH_MAJOR, 0, 63);
    absSetup(ABS_MT_TOUCH_MINOR, 0, 63);
    absSetup(ABS_MT_POSITION_X, 0, 1079);
    absSetup(ABS_MT_POSITION_Y, 0, 2399);
    absSetup(ABS_MT_TRACKING_ID, 0, 65535);
    absSetup(ABS_MT_PRESSURE, 0, 255);
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x18d1;
    setup.id.product = 0x7e57;
    strcpy(setup.name, "touchinject");
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("uinput create");
        return 1;
    }
    // Give InputReader time to add and configure the device.
    sleep(2);

    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    start.tv_sec += 1; // Leave time to read the first lines.
    printf("start_ns %lld\n", (long long)start.tv_sec * 1000000000LL + start.tv_nsec);
    fflush(stdout);

    char line[128];
    int trackingId = 100;
    while (fgets(line, sizeof(line), stdin)) {
        char kind;
        double ms;
        int x = 0, y = 0;
        if (sscanf(line, " %c %lf %d %d", &kind, &ms, &x, &y) < 2)
            continue;
        long long ns = (long long)start.tv_nsec + (long long)(ms * 1000000.0);
        struct timespec when = {start.tv_sec + ns / 1000000000LL, ns % 1000000000LL};
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &when, NULL);
        switch (kind) {
        case 'D':
            emit(EV_ABS, ABS_MT_SLOT, 0);
            emit(EV_ABS, ABS_MT_TRACKING_ID, trackingId++);
            emit(EV_ABS, ABS_MT_POSITION_X, x);
            emit(EV_ABS, ABS_MT_POSITION_Y, y);
            emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 12);
            emit(EV_ABS, ABS_MT_TOUCH_MINOR, 10);
            emit(EV_ABS, ABS_MT_PRESSURE, 60);
            emit(EV_KEY, BTN_TOUCH, 1);
            emit(EV_SYN, SYN_REPORT, 0);
            break;
        case 'M':
            emit(EV_ABS, ABS_MT_POSITION_X, x);
            emit(EV_ABS, ABS_MT_POSITION_Y, y);
            emit(EV_SYN, SYN_REPORT, 0);
            break;
        case 'U':
            emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
            emit(EV_KEY, BTN_TOUCH, 0);
            emit(EV_SYN, SYN_REPORT, 0);
            break;
        }
    }
    usleep(200000);
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
