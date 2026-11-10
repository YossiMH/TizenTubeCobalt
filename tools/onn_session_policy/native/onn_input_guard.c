#define _GNU_SOURCE
#include "policy.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/file.h>
#include <sys/signalfd.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define MAX_DEVICES 32
#define OUTPUT_SIZE 65536
#define COMMAND_SIZE 256
#define KEY_BYTES ((KEY_MAX + 8) / 8)

typedef enum { SLOT_EMPTY, SLOT_OPEN } SlotPhase;
typedef enum { BRIDGE_ABSENT, BRIDGE_STARTING, BRIDGE_READY } BridgePhase;
typedef enum { RUN_CONTINUE, RUN_STOP } RunPhase;
typedef enum { MODE_CONTROLLED, MODE_OBSERVE } RunMode;

typedef struct {
    SlotPhase phase;
    int fd;
    unsigned input_id;
    InputSource source;
    GrabPhase grab;
    char name[80];
    char path[80];
    uint64_t inode;
} Device;

typedef struct {
    Device devices[MAX_DEVICES];
    Lease lease;
    VolumeState volume;
    BridgePhase bridge_phase;
    int bridge_fd;
    int signal_fd;
    int lock_fd;
    int64_t bridge_created_ms;
    int64_t next_discovery_ms;
    int64_t end_ms;
    RunMode mode;
    RunPhase phase;
    int result;
    char output[OUTPUT_SIZE];
    size_t output_length;
    char command[COMMAND_SIZE];
    size_t command_length;
} Runtime;

static int64_t clock_ms(clockid_t which) {
    struct timespec now;
    if (clock_gettime(which, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* The only control channel is the inherited subprocess pipe. No listener or
 * world-writable control file exists. Output backpressure fails open. */
static void emit(Runtime *r, const char *format, ...) {
    if (r->phase == RUN_STOP && r->result != 0) return;
    char line[1200];
    va_list ap;
    va_start(ap, format);
    int count = vsnprintf(line, sizeof(line), format, ap);
    va_end(ap);
    if (count < 0 || (size_t)count + 1 >= sizeof(line) ||
        (size_t)count + 1 > OUTPUT_SIZE - r->output_length) {
        r->phase = RUN_STOP;
        r->result = 20;
        return;
    }
    memcpy(r->output + r->output_length, line, (size_t)count);
    r->output_length += (size_t)count;
    r->output[r->output_length++] = '\n';
}

static void flush_output(Runtime *r) {
    while (r->output_length != 0) {
        ssize_t count = write(STDOUT_FILENO, r->output, r->output_length);
        if (count > 0) {
            r->output_length -= (size_t)count;
            memmove(r->output, r->output + count, r->output_length);
        } else if (count < 0 && errno == EINTR) {
            continue;
        } else if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return;
        } else {
            r->phase = RUN_STOP;
            r->result = 21;
            return;
        }
    }
}

static int bridge_write(int fd, unsigned code, int value) {
    struct input_event events[2];
    memset(events, 0, sizeof(events));
    events[0].type = EV_KEY;
    events[0].code = (uint16_t)code;
    events[0].value = value;
    events[1].type = EV_SYN;
    events[1].code = SYN_REPORT;
    ssize_t result;
    do { result = write(fd, events, sizeof(events)); } while (result < 0 && errno == EINTR);
    return result == (ssize_t)sizeof(events) ? 0 : -1;
}

static int bridge_create(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, KEY_MUTE) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEDOWN) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEUP) < 0) {
        int saved = errno; close(fd); errno = saved; return -1;
    }
    struct uinput_user_dev device;
    memset(&device, 0, sizeof(device));
    (void)snprintf(device.name, sizeof(device.name), "Onn Policy Volume");
    device.id.bustype = BUS_VIRTUAL;
    device.id.vendor = 0x1209;
    device.id.product = 0x1110;
    device.id.version = 1;
    if (write(fd, &device, sizeof(device)) != (ssize_t)sizeof(device) ||
        ioctl(fd, UI_DEV_CREATE) < 0) {
        int saved = errno; close(fd); errno = saved; return -1;
    }
    return fd;
}

static void bridge_destroy(Runtime *r) {
    if (r->bridge_fd >= 0) {
        (void)ioctl(r->bridge_fd, UI_DEV_DESTROY);
        (void)close(r->bridge_fd);
    }
    r->bridge_fd = -1;
    r->bridge_phase = BRIDGE_ABSENT;
}

static void release_volume(Runtime *r, VolumeRelease release) {
    for (unsigned i = 0; i < release.count; ++i) {
        if (r->bridge_fd >= 0 &&
            bridge_write(r->bridge_fd, release.events[i].code, 0) < 0) {
            r->phase = RUN_STOP;
            r->result = 22;
        }
    }
    r->volume = release.next;
}

static void release_grabs(Runtime *r, const char *reason) {
    release_volume(r, volume_release_all(r->volume));
    for (unsigned i = 0; i < MAX_DEVICES; ++i) {
        Device *d = &r->devices[i];
        if (d->phase == SLOT_OPEN && d->grab == GRAB_ACTIVE) {
            /* Closing always releases the kernel grab even if ungrab fails. */
            int result = ioctl(d->fd, EVIOCGRAB, 0);
            emit(r, "{\"kind\":\"released\",\"input_id\":%u,\"reason\":\"%s\",\"ioctl_result\":%d}",
                 d->input_id, reason, result);
            d->grab = GRAB_INACTIVE;
            if (result < 0) { (void)close(d->fd); d->phase = SLOT_EMPTY; }
        }
    }
}

static void close_device(Runtime *r, unsigned slot, const char *reason) {
    Device *d = &r->devices[slot];
    if (d->phase != SLOT_OPEN) return;
    release_volume(r, volume_release_device(r->volume, slot));
    emit(r, "{\"kind\":\"device_removed\",\"source\":\"%s\",\"input_id\":%u,\"reason\":\"%s\"}",
         source_name(d->source), d->input_id, reason);
    (void)close(d->fd);
    memset(d, 0, sizeof(*d));
}

static int device_keys_clear(int fd) {
    unsigned char keys[KEY_BYTES];
    memset(keys, 0, sizeof(keys));
    if (ioctl(fd, EVIOCGKEY(sizeof(keys)), keys) < 0) return -1;
    for (size_t i = 0; i < sizeof(keys); ++i) if (keys[i] != 0) return 0;
    return 1;
}

static void try_grabs(Runtime *r) {
    if (r->lease.phase != LEASE_ARMED || r->bridge_phase != BRIDGE_READY) return;
    for (unsigned i = 0; i < MAX_DEVICES; ++i) {
        Device *d = &r->devices[i];
        if (d->phase != SLOT_OPEN || d->source != SOURCE_PHYSICAL ||
            d->grab != GRAB_INACTIVE) continue;
        /* Never steal a device between an Android-observed DOWN and its UP. */
        int clear = device_keys_clear(d->fd);
        if (clear == 0) continue;
        if (clear < 0 || ioctl(d->fd, EVIOCGRAB, 1) < 0) {
            emit(r, "{\"kind\":\"error\",\"operation\":\"grab\",\"input_id\":%u,\"errno\":%d}",
                 d->input_id, errno);
            r->phase = RUN_STOP;
            r->result = 23;
            return;
        }
        /* A press arriving between the snapshot and grab is allowed through.
         * Release immediately and retry after that physical key is released. */
        if (device_keys_clear(d->fd) != 1) {
            if (ioctl(d->fd, EVIOCGRAB, 0) < 0) close_device(r, i, "arm_race");
            continue;
        }
        d->grab = GRAB_ACTIVE;
        emit(r, "{\"kind\":\"grabbed\",\"source\":\"physical\",\"input_id\":%u,\"boottime_ms\":%" PRId64 "}",
             d->input_id, clock_ms(CLOCK_BOOTTIME));
    }
}

static void discover(Runtime *r) {
    for (unsigned i = 0; i < MAX_DEVICES; ++i) {
        Device *d = &r->devices[i];
        struct stat current;
        if (d->phase == SLOT_OPEN &&
            (stat(d->path, &current) < 0 || current.st_ino != d->inode))
            close_device(r, i, "disconnected");
    }
    DIR *directory = opendir("/dev/input");
    if (directory == NULL) {
        emit(r, "{\"kind\":\"error\",\"operation\":\"discover\",\"errno\":%d}", errno);
        r->phase = RUN_STOP;
        r->result = 24;
        return;
    }
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        unsigned event_id;
        char extra;
        if (sscanf(entry->d_name, "event%u%c", &event_id, &extra) != 1) continue;
        unsigned slot = MAX_DEVICES;
        unsigned existing = MAX_DEVICES;
        for (unsigned i = 0; i < MAX_DEVICES; ++i) {
            if (r->devices[i].phase == SLOT_EMPTY && slot == MAX_DEVICES) slot = i;
            if (r->devices[i].phase == SLOT_OPEN && r->devices[i].input_id == event_id) existing = i;
        }
        if (existing != MAX_DEVICES || slot == MAX_DEVICES) continue;
        char path[80], name[80];
        (void)snprintf(path, sizeof(path), "/dev/input/event%u", event_id);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        struct input_id id;
        struct stat file;
        memset(name, 0, sizeof(name));
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0 ||
            ioctl(fd, EVIOCGID, &id) < 0 || fstat(fd, &file) < 0) {
            (void)close(fd); continue;
        }
        InputSource source = classify_device(name, id.bustype, id.vendor, id.product);
        if (source == SOURCE_BRIDGE) {
            if (r->bridge_phase == BRIDGE_STARTING &&
                clock_ms(CLOCK_BOOTTIME) - r->bridge_created_ms >= 500) {
                r->bridge_phase = BRIDGE_READY;
                emit(r, "{\"kind\":\"volume_ready\",\"input_id\":%u}", event_id);
            }
            (void)close(fd); continue;
        }
        if (source == SOURCE_OTHER) { (void)close(fd); continue; }
        Device *d = &r->devices[slot];
        memset(d, 0, sizeof(*d));
        d->phase = SLOT_OPEN;
        d->fd = fd;
        d->input_id = event_id;
        d->source = source;
        d->inode = file.st_ino;
        (void)snprintf(d->name, sizeof(d->name), "%s", name);
        (void)snprintf(d->path, sizeof(d->path), "%s", path);
        emit(r, "{\"kind\":\"device\",\"source\":\"%s\",\"input_id\":%u,\"name\":\"%s\",\"bus\":%u,\"vendor\":%u,\"product\":%u}",
             source_name(source), event_id, name, id.bustype, id.vendor, id.product);
    }
    (void)closedir(directory);
}

static void read_device(Runtime *r, unsigned slot) {
    Device *d = &r->devices[slot];
    struct input_event events[32];
    ssize_t bytes;
    unsigned batches = 0;
    while ((bytes = read(d->fd, events, sizeof(events))) > 0) {
        if (bytes % (ssize_t)sizeof(events[0]) != 0) {
            r->phase = RUN_STOP; r->result = 25; return;
        }
        size_t count = (size_t)bytes / sizeof(events[0]);
        for (size_t i = 0; i < count; ++i) {
            struct input_event *e = &events[i];
            if (e->type == EV_SYN && e->code == SYN_DROPPED) {
                /* No guessed recovery after lost events: release and restart. */
                emit(r, "{\"kind\":\"error\",\"operation\":\"input_overflow\",\"input_id\":%u}", d->input_id);
                r->phase = RUN_STOP; r->result = 26; return;
            }
            if (e->type != EV_KEY) continue;
            KeyAction action = key_action(d->source, d->grab, e->code);
            if (action == KEY_FORWARD_VOLUME) {
                VolumeResult volume = volume_update(r->volume, slot, e->code, e->value);
                if (volume.transition == VOLUME_EMIT &&
                    bridge_write(r->bridge_fd, volume.code, volume.value) < 0) {
                    emit(r, "{\"kind\":\"error\",\"operation\":\"volume_write\",\"errno\":%d}", errno);
                    r->phase = RUN_STOP; r->result = 27; return;
                }
                r->volume = volume.next;
            }
            const char *action_name = action == KEY_BLOCK ? "blocked" :
                                      action == KEY_FORWARD_VOLUME ? "volume_forwarded" : "observed";
            emit(r, "{\"kind\":\"key\",\"source\":\"%s\",\"input_id\":%u,\"name\":\"%s\",\"code\":%u,\"value\":%d,\"action\":\"%s\",\"monotonic_ms\":%" PRId64 ",\"boottime_ms\":%" PRId64 ",\"wall_ms\":%" PRId64 "}",
                 source_name(d->source), d->input_id, d->name, e->code, e->value, action_name,
                 clock_ms(CLOCK_MONOTONIC), clock_ms(CLOCK_BOOTTIME), clock_ms(CLOCK_REALTIME));
            if (r->phase == RUN_STOP) return;
        }
        /* Bounded input work keeps lease checks and phone controls responsive. */
        if (++batches >= 4) return;
    }
    if (bytes == 0 || (bytes < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR))
        close_device(r, slot, "read_closed");
}

static void apply_command(Runtime *r, const char *command) {
    int64_t ttl, end;
    char extra;
    if (strcmp(command, "DISARM") == 0) {
        release_grabs(r, "controller_disarm");
        r->lease = (Lease){0};
        emit(r, "{\"kind\":\"disarmed\",\"reason\":\"controller_disarm\"}");
    } else if (strcmp(command, "QUIT") == 0) {
        r->phase = RUN_STOP;
    } else if (strcmp(command, "STATUS") == 0) {
        emit(r, "{\"kind\":\"status\",\"lease\":\"%s\",\"volume\":\"%s\",\"boottime_ms\":%" PRId64 "}",
             r->lease.phase == LEASE_ARMED ? "armed" : "disarmed",
             r->bridge_phase == BRIDGE_READY ? "ready" : "absent", clock_ms(CLOCK_BOOTTIME));
    } else if (sscanf(command, "ARM %" SCNd64 " %" SCNd64 " %c", &ttl, &end, &extra) == 2) {
        r->lease = lease_arm(r->lease, clock_ms(CLOCK_BOOTTIME), clock_ms(CLOCK_REALTIME), ttl, end);
        if (r->lease.phase != LEASE_ARMED) {
            release_grabs(r, "invalid_lease");
            emit(r, "{\"kind\":\"disarmed\",\"reason\":\"invalid_lease\"}");
            return;
        }
        if (r->bridge_phase == BRIDGE_ABSENT) {
            r->bridge_fd = bridge_create();
            if (r->bridge_fd < 0) {
                emit(r, "{\"kind\":\"error\",\"operation\":\"volume_create\",\"errno\":%d}", errno);
                r->phase = RUN_STOP; r->result = 28; return;
            }
            r->bridge_phase = BRIDGE_STARTING;
            r->bridge_created_ms = clock_ms(CLOCK_BOOTTIME);
            r->next_discovery_ms = 0;
        }
        emit(r, "{\"kind\":\"lease\",\"phase\":\"armed\",\"heartbeat_deadline_ms\":%" PRId64 ",\"session_end_ms\":%" PRId64 "}",
             r->lease.heartbeat_deadline_ms, r->lease.session_epoch_ms);
    } else {
        release_grabs(r, "invalid_command");
        r->lease = (Lease){0};
        emit(r, "{\"kind\":\"disarmed\",\"reason\":\"invalid_command\"}");
    }
}

static void read_commands(Runtime *r) {
    char buffer[512];
    ssize_t count;
    while ((count = read(STDIN_FILENO, buffer, sizeof(buffer))) > 0) {
        for (ssize_t i = 0; i < count; ++i) {
            if (buffer[i] == '\n') {
                r->command[r->command_length] = '\0';
                apply_command(r, r->command);
                r->command_length = 0;
                if (r->phase == RUN_STOP) return;
            } else if (buffer[i] == '\r') {
                continue;
            } else if (buffer[i] == '\0' || r->command_length + 1 >= COMMAND_SIZE) {
                r->phase = RUN_STOP; r->result = 29; return;
            } else {
                r->command[r->command_length++] = buffer[i];
            }
        }
    }
    if (count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
        emit(r, "{\"kind\":\"controller_closed\"}");
        r->phase = RUN_STOP;
    }
}

static int singleton_lock(void) {
    int fd = open("/data/local/tmp/onn-policy-20261110/input_guard.lock",
                  O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return -1;
    if (flock(fd, LOCK_EX | LOCK_NB) < 0) {
        int saved = errno; (void)close(fd); errno = saved; return -1;
    }
    return fd;
}

static int probe(void) {
    Runtime r;
    memset(&r, 0, sizeof(r));
    r.bridge_fd = -1;
    r.lock_fd = singleton_lock();
    if (r.lock_fd < 0) { fprintf(stderr, "probe lock unavailable: %s\n", strerror(errno)); return 9; }
    discover(&r);
    unsigned found = 0;
    int result = 0;
    for (unsigned i = 0; i < MAX_DEVICES; ++i) {
        Device *d = &r.devices[i];
        if (d->phase != SLOT_OPEN || d->source != SOURCE_PHYSICAL) continue;
        ++found;
        int clear = device_keys_clear(d->fd);
        if (clear != 1) {
            emit(&r, "{\"kind\":\"probe_grab\",\"result\":\"busy_or_unreadable\",\"input_id\":%u}", d->input_id);
            result = 3;
            continue;
        }
        int grabbed = ioctl(d->fd, EVIOCGRAB, 1);
        int saved = errno;
        if (grabbed == 0) {
            int released = ioctl(d->fd, EVIOCGRAB, 0);
            if (released < 0) result = 4;
            emit(&r, "{\"kind\":\"probe_grab\",\"result\":\"%s\",\"input_id\":%u}",
                 released == 0 ? "passed_and_released" : "close_released", d->input_id);
        } else {
            emit(&r, "{\"kind\":\"probe_grab\",\"result\":\"denied\",\"input_id\":%u,\"errno\":%d}", d->input_id, saved);
            result = 5;
        }
        (void)close(d->fd);
        d->phase = SLOT_EMPTY;
    }
    if (found == 0) { emit(&r, "{\"kind\":\"probe_grab\",\"result\":\"physical_missing\"}"); result = 6; }
    r.bridge_fd = bridge_create();
    if (r.bridge_fd < 0) {
        emit(&r, "{\"kind\":\"probe_volume\",\"result\":\"denied\",\"errno\":%d}", errno);
        result = 7;
    } else {
        r.bridge_phase = BRIDGE_STARTING;
        r.bridge_created_ms = clock_ms(CLOCK_BOOTTIME);
        int64_t until = clock_ms(CLOCK_BOOTTIME) + 1500;
        while (r.bridge_phase != BRIDGE_READY && clock_ms(CLOCK_BOOTTIME) < until) {
            (void)poll(NULL, 0, 20);
            discover(&r);
        }
        emit(&r, "{\"kind\":\"probe_volume\",\"result\":\"%s\",\"keys_sent\":0}",
             r.bridge_phase == BRIDGE_READY ? "created_ready_destroyed" : "created_not_ready");
        if (r.bridge_phase != BRIDGE_READY) result = 8;
        bridge_destroy(&r);
    }
    for (unsigned i = 0; i < MAX_DEVICES; ++i)
        if (r.devices[i].phase == SLOT_OPEN) (void)close(r.devices[i].fd);
    flush_output(&r);
    (void)close(r.lock_fd);
    return result;
}

static int run(RunMode mode, int64_t duration_ms) {
    Runtime r;
    memset(&r, 0, sizeof(r));
    r.bridge_fd = -1;
    r.signal_fd = -1;
    r.lock_fd = -1;
    r.mode = mode;
    if (mode == MODE_CONTROLLED) {
        r.lock_fd = singleton_lock();
        if (r.lock_fd < 0) { fprintf(stderr, "guard already running or lock unavailable: %s\n", strerror(errno)); return 36; }
    }
    r.end_ms = duration_ms > 0 ? clock_ms(CLOCK_BOOTTIME) + duration_ms : INT64_MAX;
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGTERM);
    sigaddset(&signals, SIGINT);
    sigaddset(&signals, SIGHUP);
    sigaddset(&signals, SIGPIPE);
    if (sigprocmask(SIG_BLOCK, &signals, NULL) < 0) return 30;
    r.signal_fd = signalfd(-1, &signals, SFD_CLOEXEC | SFD_NONBLOCK);
    if (r.signal_fd < 0) return 31;
    int output_flags = fcntl(STDOUT_FILENO, F_GETFL);
    if (output_flags < 0 || fcntl(STDOUT_FILENO, F_SETFL, output_flags | O_NONBLOCK) < 0) return 32;
    if (mode == MODE_CONTROLLED) {
        int input_flags = fcntl(STDIN_FILENO, F_GETFL);
        if (input_flags < 0 || fcntl(STDIN_FILENO, F_SETFL, input_flags | O_NONBLOCK) < 0) return 33;
    }
    emit(&r, "{\"kind\":\"ready\",\"version\":1,\"mode\":\"%s\",\"pid\":%ld,\"max_lease_ms\":15000}",
         mode == MODE_CONTROLLED ? "controlled" : "observe", (long)getpid());
    while (r.phase == RUN_CONTINUE) {
        int64_t now = clock_ms(CLOCK_BOOTTIME);
        if (now >= r.end_ms) break;
        Lease next = lease_tick(r.lease, now, clock_ms(CLOCK_REALTIME));
        if (r.lease.phase == LEASE_ARMED && next.phase != LEASE_ARMED) {
            release_grabs(&r, "lease_expired");
            emit(&r, "{\"kind\":\"disarmed\",\"reason\":\"lease_expired\"}");
        }
        r.lease = next;
        if (now >= r.next_discovery_ms) {
            discover(&r);
            r.next_discovery_ms = now + (r.bridge_phase == BRIDGE_STARTING ? 50 : 1000);
        }
        if (r.bridge_phase == BRIDGE_STARTING && now - r.bridge_created_ms > 2000) {
            emit(&r, "{\"kind\":\"error\",\"operation\":\"volume_ready_timeout\"}");
            r.phase = RUN_STOP; r.result = 34;
        }
        try_grabs(&r);
        flush_output(&r);
        if (r.phase == RUN_STOP) break;
        struct pollfd fds[MAX_DEVICES + 3];
        unsigned slots[MAX_DEVICES];
        nfds_t count = 3;
        fds[0] = (struct pollfd){mode == MODE_CONTROLLED ? STDIN_FILENO : -1, POLLIN, 0};
        fds[1] = (struct pollfd){r.signal_fd, POLLIN, 0};
        fds[2] = (struct pollfd){r.output_length != 0 ? STDOUT_FILENO : -1, POLLOUT, 0};
        for (unsigned i = 0; i < MAX_DEVICES; ++i) if (r.devices[i].phase == SLOT_OPEN) {
            slots[count - 3] = i;
            fds[count++] = (struct pollfd){r.devices[i].fd, POLLIN, 0};
        }
        int64_t poll_deadline = r.next_discovery_ms < r.end_ms ? r.next_discovery_ms : r.end_ms;
        if (r.lease.phase == LEASE_ARMED) {
            if (r.lease.heartbeat_deadline_ms < poll_deadline)
                poll_deadline = r.lease.heartbeat_deadline_ms;
            if (r.lease.session_boottime_deadline_ms < poll_deadline)
                poll_deadline = r.lease.session_boottime_deadline_ms;
        }
        int64_t remaining = poll_deadline - clock_ms(CLOCK_BOOTTIME);
        int timeout_ms = remaining <= 0 ? 0 : remaining > 1000 ? 1000 : (int)remaining;
        int waited = poll(fds, count, timeout_ms);
        if (waited < 0 && errno != EINTR) { r.phase = RUN_STOP; r.result = 35; break; }
        /* Controller state changes precede input handling in every batch. */
        if ((fds[0].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) != 0) read_commands(&r);
        if ((fds[1].revents & POLLIN) != 0) r.phase = RUN_STOP;
        if (r.phase == RUN_STOP) break;
        for (nfds_t i = 3; i < count; ++i) {
            if ((fds[i].revents & POLLIN) != 0) read_device(&r, slots[i - 3]);
            if ((fds[i].revents & (POLLHUP | POLLERR | POLLNVAL)) != 0)
                close_device(&r, slots[i - 3], "poll_closed");
            if (r.phase == RUN_STOP) break;
        }
    }
    release_grabs(&r, "process_exit");
    bridge_destroy(&r);
    for (unsigned i = 0; i < MAX_DEVICES; ++i)
        if (r.devices[i].phase == SLOT_OPEN) (void)close(r.devices[i].fd);
    (void)close(r.signal_fd);
    if (r.lock_fd >= 0) (void)close(r.lock_fd);
    emit(&r, "{\"kind\":\"stopped\",\"result\":%d}", r.result);
    int64_t until = clock_ms(CLOCK_BOOTTIME) + 200;
    do { flush_output(&r); if (r.output_length != 0) (void)poll(NULL, 0, 10); }
    while (r.output_length != 0 && clock_ms(CLOCK_BOOTTIME) < until);
    return r.result;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--probe") == 0) return probe();
    if (argc == 2 && strcmp(argv[1], "--run") == 0) return run(MODE_CONTROLLED, 0);
    if (argc == 3 && strcmp(argv[1], "--monitor-ms") == 0) {
        char *end;
        long duration = strtol(argv[2], &end, 10);
        if (*end == '\0' && duration >= 1 && duration <= 30000) return run(MODE_OBSERVE, duration);
    }
    fputs("Usage: onn_input_guard --probe | --run | --monitor-ms 1..30000\n", stderr);
    return 2;
}
