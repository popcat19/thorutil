// SPDX-License-Identifier: Apache-2.0
// Gamepad to OS mouse bridge for AYN Thor.
// Reads the virtual Xbox pad from com.odin.mapping. While active it grabs the pad,
// drives a uinput mouse, and re-emits unconsumed input on a passthrough clone.

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define PAD_NAME "Xbox Wireless Controller"
#define MOUSE_NAME "ThorUtil Mouse"
#define PASS_NAME "ThorUtil Gamepad"
#define AXIS_MAX 32767.0
#define TICK_MS 8
#define DOUBLE_MS 300
#define NBITS(x) ((((x) - 1) / (8 * sizeof(long))) + 1)
#define TEST_BIT(b, a) (((a)[(b) / (8 * sizeof(long))] >> ((b) % (8 * sizeof(long)))) & 1)

static double deadzone = 0.15;
static double pointer_speed = 1000.0;
static double scroll_speed = 12.0;
static const char *state_path = "/data/local/tmp/thorutil-mouse.state";
static const char *lock_path = "/data/local/tmp/thorutil-mouse.lock";

static volatile sig_atomic_t quit_flag;
static volatile sig_atomic_t toggle_flag;

struct bridge {
    int pad, mouse, pass;
    int active;
    int ax[ABS_CNT];
    long sel_at;
    int want_toggle;
    double acc_x, acc_y, acc_w, acc_h;
    int held[5];
};

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void on_term(int s) { (void)s; quit_flag = 1; }
static void on_usr1(int s) { (void)s; toggle_flag = 1; }

static void emit(int fd, int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = val;
    if (write(fd, &ev, sizeof(ev)) < 0 && errno != EAGAIN) perror("write");
}

static void write_state(int active) {
    FILE *f = fopen(state_path, "w");
    if (!f) return;
    fprintf(f, "%d %d\n", active, (int)getpid());
    fclose(f);
    chmod(state_path, 0666);
}

static int find_pad(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d)) && found < 0) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char path[64], name[128] = {0};
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && !strcmp(name, PAD_NAME)) found = fd;
        else close(fd);
    }
    closedir(d);
    return found;
}

static int finish_dev(int fd, const char *name, struct input_id id) {
    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    snprintf(us.name, sizeof(us.name), "%s", name);
    us.id = id;
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("uinput create");
        close(fd);
        return -1;
    }
    return fd;
}

static int make_mouse(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    static const int keys[] = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE, BTN_SIDE, BTN_EXTRA};
    static const int rels[] = {REL_X, REL_Y, REL_WHEEL, REL_HWHEEL};
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (size_t i = 0; i < sizeof(keys) / sizeof(*keys); i++) ioctl(fd, UI_SET_KEYBIT, keys[i]);
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    for (size_t i = 0; i < sizeof(rels) / sizeof(*rels); i++) ioctl(fd, UI_SET_RELBIT, rels[i]);
    struct input_id id = {BUS_VIRTUAL, 0x2020, 0x7430, 1};
    return finish_dev(fd, MOUSE_NAME, id);
}

// Clone of the pad's capabilities so Android keeps the same keylayout for passthrough.
static int make_pass(int pad) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    unsigned long kb[NBITS(KEY_CNT)] = {0}, ab[NBITS(ABS_CNT)] = {0};
    ioctl(pad, EVIOCGBIT(EV_KEY, sizeof(kb)), kb);
    ioctl(pad, EVIOCGBIT(EV_ABS, sizeof(ab)), ab);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (int k = 0; k < KEY_CNT; k++)
        if (TEST_BIT(k, kb)) ioctl(fd, UI_SET_KEYBIT, k);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    for (int a = 0; a < ABS_CNT; a++) {
        if (!TEST_BIT(a, ab)) continue;
        struct uinput_abs_setup as;
        memset(&as, 0, sizeof(as));
        as.code = a;
        ioctl(pad, EVIOCGABS(a), &as.absinfo);
        as.absinfo.value = 0;
        ioctl(fd, UI_SET_ABSBIT, a);
        ioctl(fd, UI_ABS_SETUP, &as);
    }
    struct input_id id;
    ioctl(pad, EVIOCGID, &id);
    return finish_dev(fd, PASS_NAME, id);
}

static void destroy(int *fd) {
    if (*fd < 0) return;
    ioctl(*fd, UI_DEV_DESTROY);
    close(*fd);
    *fd = -1;
}

static int mouse_btn_index(int code) {
    switch (code) {
    // BTN_NORTH/BTN_WEST are X/Y on this pad (keylayout 0x133/0x134).
    case BTN_SOUTH: return 0;  // A -> left
    case BTN_NORTH: return 1;  // X -> right
    case BTN_THUMBR: return 2; // R3 -> middle
    case BTN_EAST: return 3;   // B -> back
    case BTN_WEST: return 4;   // Y -> forward
    default: return -1;
    }
}

static const int mouse_btns[5] = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE, BTN_SIDE, BTN_EXTRA};

static int consumed_abs(int code) {
    return code == ABS_X || code == ABS_Y || code == ABS_Z || code == ABS_RZ;
}

static void set_active(struct bridge *b, int on) {
    if (on == b->active) return;
    if (on) {
        b->mouse = make_mouse();
        b->pass = make_pass(b->pad);
        if (b->mouse < 0 || b->pass < 0 || ioctl(b->pad, EVIOCGRAB, 1) < 0) {
            perror("activate");
            destroy(&b->mouse);
            destroy(&b->pass);
            return;
        }
        b->acc_x = b->acc_y = b->acc_w = b->acc_h = 0;
    } else {
        for (int i = 0; i < 5; i++)
            if (b->held[i]) emit(b->mouse, EV_KEY, mouse_btns[i], 0);
        if (b->mouse >= 0) emit(b->mouse, EV_SYN, SYN_REPORT, 0);
        memset(b->held, 0, sizeof(b->held));
        ioctl(b->pad, EVIOCGRAB, 0);
        destroy(&b->mouse);
        destroy(&b->pass);
    }
    b->active = on;
    write_state(on);
    fprintf(stderr, "mouse mode %s\n", on ? "on" : "off");
}

// Radial deadzone, rescaled to 0..1, squared for fine control near center.
static void stick(int rx, int ry, double *ox, double *oy) {
    double x = rx / AXIS_MAX, y = ry / AXIS_MAX;
    double m = sqrt(x * x + y * y);
    if (m <= deadzone) { *ox = *oy = 0; return; }
    double r = (m - deadzone) / (1.0 - deadzone);
    if (r > 1.0) r = 1.0;
    double k = r * r / m;
    *ox = x * k;
    *oy = y * k;
}

static int take(double *acc) {
    int v = (int)*acc;
    *acc -= v;
    return v;
}

static void tick(struct bridge *b, double dt) {
    double px, py, sx, sy;
    stick(b->ax[ABS_X], b->ax[ABS_Y], &px, &py);
    stick(b->ax[ABS_Z], b->ax[ABS_RZ], &sx, &sy);
    if (!px && !py && !sx && !sy) { b->acc_x = b->acc_y = b->acc_w = b->acc_h = 0; return; }
    b->acc_x += px * pointer_speed * dt;
    b->acc_y += py * pointer_speed * dt;
    b->acc_w += -sy * scroll_speed * dt;
    b->acc_h += sx * scroll_speed * dt;
    int dx = take(&b->acc_x), dy = take(&b->acc_y), dw = take(&b->acc_w), dh = take(&b->acc_h);
    if (!dx && !dy && !dw && !dh) return;
    if (dx) emit(b->mouse, EV_REL, REL_X, dx);
    if (dy) emit(b->mouse, EV_REL, REL_Y, dy);
    if (dw) emit(b->mouse, EV_REL, REL_WHEEL, dw);
    if (dh) emit(b->mouse, EV_REL, REL_HWHEEL, dh);
    emit(b->mouse, EV_SYN, SYN_REPORT, 0);
}

static void handle(struct bridge *b, const struct input_event *ev) {
    if (ev->type == EV_ABS && ev->code < ABS_CNT) b->ax[ev->code] = ev->value;
    // Select still passes through; the double press only adds the toggle.
    if (ev->type == EV_KEY && ev->code == BTN_SELECT && ev->value == 1) {
        long t = now_ms();
        if (b->sel_at && t - b->sel_at <= DOUBLE_MS) { b->want_toggle = 1; b->sel_at = 0; }
        else b->sel_at = t;
    }
    if (!b->active) return;

    if (ev->type == EV_KEY) {
        int i = mouse_btn_index(ev->code);
        if (i >= 0) {
            if (ev->value == 2) return;
            b->held[i] = ev->value != 0;
            emit(b->mouse, EV_KEY, mouse_btns[i], b->held[i]);
            emit(b->mouse, EV_SYN, SYN_REPORT, 0);
            return;
        }
    }
    if (ev->type == EV_ABS && consumed_abs(ev->code)) return;
    emit(b->pass, ev->type, ev->code, ev->value);
}

static void usage(const char *argv0) {
    fprintf(stderr,
            "usage: %s [-a] [-d deadzone 0-0.9] [-p pointer px/s] [-s scroll detents/s] [-f state file]\n"
            "  -a  start in mouse mode\n"
            "  SIGUSR1 toggles mouse mode; double-press Select also toggles\n",
            argv0);
}

int main(int argc, char **argv) {
    int start_active = 0, opt;
    while ((opt = getopt(argc, argv, "ad:p:s:f:h")) != -1) {
        switch (opt) {
        case 'a': start_active = 1; break;
        case 'd': deadzone = atof(optarg); break;
        case 'p': pointer_speed = atof(optarg); break;
        case 's': scroll_speed = atof(optarg); break;
        case 'f': state_path = optarg; break;
        default: usage(argv[0]); return opt == 'h' ? 0 : 2;
        }
    }
    if (deadzone < 0 || deadzone > 0.9) { usage(argv[0]); return 2; }

    // Single instance: the Magisk module and the app can both try to launch this.
    int lock = open(lock_path, O_RDONLY | O_CREAT | O_CLOEXEC, 0644);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        fprintf(stderr, "already running\n");
        return 1;
    }
    chmod(lock_path, 0644);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sa.sa_handler = on_usr1;
    sigaction(SIGUSR1, &sa, NULL);

    struct bridge b;
    memset(&b, 0, sizeof(b));
    b.pad = b.mouse = b.pass = -1;
    write_state(0);

    while (!quit_flag) {
        if (b.pad < 0) {
            b.pad = find_pad();
            if (b.pad < 0) { sleep(2); continue; }
            fprintf(stderr, "pad attached\n");
            memset(b.ax, 0, sizeof(b.ax));
            if (start_active) set_active(&b, 1);
        }

        int timeout = b.active ? TICK_MS : -1;
        struct pollfd pfd = {.fd = b.pad, .events = POLLIN};
        long t0 = now_ms();
        int r = poll(&pfd, 1, timeout);

        if (toggle_flag) { toggle_flag = 0; set_active(&b, !b.active); }

        if (r > 0 && (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) goto lost;
        if (r > 0) {
            struct input_event ev[64];
            ssize_t n;
            while ((n = read(b.pad, ev, sizeof(ev))) > 0)
                for (ssize_t i = 0; i < n / (ssize_t)sizeof(*ev); i++) handle(&b, &ev[i]);
            if (n < 0 && errno != EAGAIN) goto lost;
        }

        if (b.want_toggle) { b.want_toggle = 0; set_active(&b, !b.active); }

        if (b.active) {
            static long last;
            long t = now_ms();
            if (!last || t - last > 100) last = t0;
            tick(&b, (t - last) / 1000.0);
            last = t;
        }
        continue;
    lost:
        fprintf(stderr, "pad lost, rescanning\n");
        start_active = b.active;
        set_active(&b, 0);
        close(b.pad);
        b.pad = -1;
    }

    set_active(&b, 0);
    if (b.pad >= 0) close(b.pad);
    unlink(state_path);
    return 0;
}
