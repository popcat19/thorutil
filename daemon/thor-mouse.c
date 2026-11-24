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
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PAD_NAME "Xbox Wireless Controller"
#define MOUSE_NAME "ThorUtil Mouse"
#define PASS_NAME "ThorUtil Gamepad"
#define AYN_NAME "gpio-keys"
#define AYN_SCAN 194
// The re-sent AYN tap keeps the gpio-keys name (so gpio-keys.kl applies) but a
// distinct id, so the app's key filter lets it through.
#define AYN_VENDOR 0x2020
#define AYN_PRODUCT 0x7431
#define AXIS_MAX 32767.0
#define TICK_MS 8
#define DOUBLE_MS 300
#define NBITS(x) ((((x) - 1) / (8 * sizeof(long))) + 1)
#define TEST_BIT(b, a) (((a)[(b) / (8 * sizeof(long))] >> ((b) % (8 * sizeof(long)))) & 1)

static double deadzone = 0.02;
static double pointer_speed = 1000.0;
static double scroll_speed = 12.0;
static int rotate_dpad = 0;
static const char *state_path = "/data/local/tmp/thorutil-mouse.state";
static const char *lock_path = "/data/local/tmp/thorutil-mouse.lock";
static const char *suspend_path = "/data/local/tmp/thorutil-mouse.suspend";
static const char *cmd_path = "/data/local/tmp/thorutil-mouse.cmd";
static const char *touch_path = "/data/local/tmp/thorutil-touch";
static const char *rotation_path = "/data/local/tmp/thorutil-rotation";
// Top panel is fts_ts (display 0), bottom is fts_ts_3 (display 4).
static const char *touch_names[2] = {"fts_ts", "fts_ts_3"};

static volatile sig_atomic_t quit_flag;
static volatile sig_atomic_t toggle_flag;
static volatile sig_atomic_t suspend_flag;

struct bridge {
    int pad, mouse, pass, ayn_fd;
    int active;
    int ayn;
    int back_held;
    unsigned char routed[KEY_CNT];
    int ax[ABS_CNT];
    long sel_at;
    int want_toggle;
    int suspended, resume_active;
    double acc_x, acc_y, acc_w, acc_h;
    int held[5];
    int dpad_out[4];
    int hat_out[2];
    int bottom;
    // focus.current: the newer of the last touched screen and the last app focus action.
    // Mouse mode starts on it.
    int focus;
    pid_t mover;
    int touch_fd[2];
    // ROTATION_0=0, ROTATION_90=1, ROTATION_180=2, ROTATION_270=3; [0]=top, [1]=bottom.
    int rotation[2];
};

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

// The app turns these into toast + haptic feedback.
static void notify(const char *event) {
    pid_t pid = fork();
    if (pid == 0) {
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) { dup2(nul, 1); dup2(nul, 2); }
        execl("/system/bin/am", "am", "broadcast", "-a", "dev.popcat19.thorutil.EVENT", "-p",
              "dev.popcat19.thorutil", "--es", "event", event, (char *)NULL);
        _exit(127);
    }
}

static void on_term(int s) { (void)s; quit_flag = 1; }
static void on_usr1(int s) { (void)s; toggle_flag = 1; }
static void on_usr2(int s) { (void)s; suspend_flag = 1; }

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

// vendor 0 matches any id.
static int find_dev(const char *want, int vendor) {
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
        struct input_id id;
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && !strcmp(name, want) &&
            (!vendor || (ioctl(fd, EVIOCGID, &id) >= 0 && id.vendor == vendor)))
            found = fd;
        else
            close(fd);
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

// Re-sends one AYN tap for the app; uinput devices take ~100 ms to be picked up.
static int ayn_tap(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (fd < 0) { perror("uinput"); return 1; }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, AYN_SCAN);
    struct input_id id = {BUS_VIRTUAL, AYN_VENDOR, AYN_PRODUCT, 1};
    if ((fd = finish_dev(fd, AYN_NAME, id)) < 0) return 1;
    usleep(150000);
    emit(fd, EV_KEY, AYN_SCAN, 1);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    usleep(50000);
    emit(fd, EV_KEY, AYN_SCAN, 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    usleep(200000);
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
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
// Clockwise order so adding (5 - rot) % 4 rotates D-pad keys to match display orientation.
static const int dpad_btns[4] = {BTN_DPAD_UP, BTN_DPAD_RIGHT, BTN_DPAD_DOWN, BTN_DPAD_LEFT};

static int dpad_index(int code) {
    for (int i = 0; i < 4; i++)
        if (dpad_btns[i] == code) return i;
    return -1;
}

static void sync_hat(struct bridge *b, int send_syn) {
    int rx = b->ax[ABS_HAT0X], ry = b->ax[ABS_HAT0Y];
    int d = (5 - b->rotation[b->bottom]) % 4;
    for (int i = 0; i < d; i++) { int t = rx; rx = -ry; ry = t; }
    int changed = 0;
    if (rx != b->hat_out[0]) {
        b->hat_out[0] = rx;
        emit(b->pass, EV_ABS, ABS_HAT0X, rx);
        changed = 1;
    }
    if (ry != b->hat_out[1]) {
        b->hat_out[1] = ry;
        emit(b->pass, EV_ABS, ABS_HAT0Y, ry);
        changed = 1;
    }
    if (changed && send_syn) emit(b->pass, EV_SYN, SYN_REPORT, 0);
}

// Android puts the mouse pointer on display 0 unless desktop mode on external
// displays is on; the choice is only re-read when viewports change, so nudge
// the bottom panel size by 1 px and restore it (keeps any user size override).
static const char *move_script =
    "k=force_desktop_mode_on_external_displays;"
    "[ \"$2\" = lazy ] && [ \"$(settings get global $k)\" != 1 ] && exit 0;"
    // Desktop mode pins the bottom display's rotation; unpin before it applies so
    // a held portrait doesn't flip to landscape and back. "default" restores it.
    "[ $1 = 1 ] && wm fixed-to-user-rotation -d 4 disabled;"
    "settings put global $k $1 || exit 1;"
    "o=$(wm size -d 4); s=$(echo \"$o\" | sed -n 's/Override size: //p');"
    "[ -n \"$s\" ] || s=$(echo \"$o\" | sed -n 's/Physical size: //p');"
    "wm size ${s%x*}x$((${s#*x} - 1)) -d 4;"
    "case \"$o\" in *Override*) wm size $s -d 4;; *) wm size reset -d 4;; esac;"
    "[ $1 = 1 ] || wm fixed-to-user-rotation -d 4 default";

static pid_t run_move(int bottom, int lazy) {
    pid_t pid = fork();
    if (pid == 0) {
        execl("/system/bin/sh", "sh", "-c", move_script, "sh", bottom ? "1" : "0", lazy ? "lazy" : "", (char *)NULL);
        _exit(127);
    }
    return pid;
}

static void reap(struct bridge *b) {
    if (b->mover > 0 && waitpid(b->mover, NULL, WNOHANG) == b->mover) b->mover = 0;
    pid_t p;
    while ((p = waitpid(-1, NULL, WNOHANG)) > 0)
        if (p == b->mover) b->mover = 0;
}

static void move_pointer(struct bridge *b, int bottom, int wait, int lazy) {
    reap(b);
    if (b->mover > 0) {
        if (!wait) return;
        waitpid(b->mover, NULL, 0);
        b->mover = 0;
    }
    pid_t pid = run_move(bottom, lazy);
    if (pid < 0) { perror("fork"); return; }
    b->bottom = bottom;
    if (rotate_dpad && b->active) sync_hat(b, 1);
    if (wait) waitpid(pid, NULL, 0);
    else b->mover = pid;
    fprintf(stderr, "pointer on %s\n", bottom ? "bottom" : "top");
}

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
        memset(b->dpad_out, 0, sizeof(b->dpad_out));
        memset(b->hat_out, 0, sizeof(b->hat_out));
        if (b->focus && !b->bottom) move_pointer(b, 1, 0, 0);
    } else {
        for (int i = 0; i < 5; i++)
            if (b->held[i]) emit(b->mouse, EV_KEY, mouse_btns[i], 0);
        if (b->mouse >= 0) emit(b->mouse, EV_SYN, SYN_REPORT, 0);
        for (int i = 0; i < 4; i++)
            if (b->dpad_out[i]) emit(b->pass, EV_KEY, b->dpad_out[i], 0);
        if (b->hat_out[0]) emit(b->pass, EV_ABS, ABS_HAT0X, 0);
        if (b->hat_out[1]) emit(b->pass, EV_ABS, ABS_HAT0Y, 0);
        if (b->pass >= 0) emit(b->pass, EV_SYN, SYN_REPORT, 0);
        memset(b->held, 0, sizeof(b->held));
        memset(b->dpad_out, 0, sizeof(b->dpad_out));
        memset(b->hat_out, 0, sizeof(b->hat_out));
        memset(b->routed, 0, sizeof(b->routed));
        ioctl(b->pad, EVIOCGRAB, 0);
        if (b->bottom) move_pointer(b, 0, 1, 0);
        destroy(&b->mouse);
        destroy(&b->pass);
    }
    b->active = on;
    write_state(on);
    if (!b->suspended) notify(!on ? "mouse_off" : b->bottom ? "mouse_on_bottom" : "mouse_on");
    fprintf(stderr, "mouse mode %s\n", on ? "on" : "off");
}

// Radial deadzone, rescaled to 0..1, squared for fine control near center.
// rot is display rotation (0/90/180/270 deg). Thor normal landscape=90.
static void stick(int rx, int ry, int rot, double *ox, double *oy) {
    double x = rx / AXIS_MAX, y = ry / AXIS_MAX;
    double m = sqrt(x * x + y * y);
    if (m <= deadzone) { *ox = *oy = 0; return; }
    double r = (m - deadzone) / (1.0 - deadzone);
    if (r > 1.0) r = 1.0;
    double k = r * r / m;
    x *= k; y *= k;
    // Stick axes follow the device; pointer axes follow the display. Turn by the
    // difference from the normal grip (rot 1), 90 deg per step.
    int d = (5 - rot) % 4;
    for (int i = 0; i < d; i++) { double t = x; x = -y; y = t; }
    *ox = x; *oy = y;
}

static int take(double *acc) {
    int v = (int)*acc;
    *acc -= v;
    return v;
}

static void tick(struct bridge *b, double dt) {
    double px, py, sx, sy;
    int rot = b->rotation[b->bottom];
    stick(b->ax[ABS_X], b->ax[ABS_Y], rot, &px, &py);
    stick(b->ax[ABS_Z], b->ax[ABS_RZ], rot, &sx, &sy);
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

    // AYN combos belong to the app's key filter, so hand the buttons to Android.
    if (ev->type == EV_KEY && ev->code < KEY_CNT) {
        // Pad Back is a modifier in the app; route L1/R1 while held for Back+L1/R1.
        if (ev->code == KEY_BACK && ev->value != 2) b->back_held = ev->value;
        if (ev->value == 1)
            b->routed[ev->code] = b->ayn || (b->back_held && (ev->code == BTN_TL || ev->code == BTN_TR));
        if (b->routed[ev->code]) {
            if (ev->value == 0) b->routed[ev->code] = 0;
            emit(b->pass, EV_KEY, ev->code, ev->value);
            return;
        }
    }
    if (ev->type == EV_KEY && ev->code == BTN_THUMBL) {
        if (ev->value == 1) {
            move_pointer(b, !b->bottom, 0, 0);
            notify(b->bottom ? "pointer_bottom" : "pointer_top");
        }
        return;
    }
    if (ev->type == EV_KEY) {
        int i = mouse_btn_index(ev->code);
        if (i >= 0) {
            if (ev->value == 2) return;
            b->held[i] = ev->value != 0;
            emit(b->mouse, EV_KEY, mouse_btns[i], b->held[i]);
            emit(b->mouse, EV_SYN, SYN_REPORT, 0);
            return;
        }
        int di = rotate_dpad ? dpad_index(ev->code) : -1;
        if (di >= 0) {
            int out_code;
            if (ev->value == 1) {
                int d = (5 - b->rotation[b->bottom]) % 4;
                out_code = dpad_btns[(di + d) % 4];
                b->dpad_out[di] = out_code;
            } else {
                out_code = b->dpad_out[di] ? b->dpad_out[di] : ev->code;
                if (ev->value == 0) b->dpad_out[di] = 0;
            }
            emit(b->pass, EV_KEY, out_code, ev->value);
            return;
        }
    }
    if (rotate_dpad && ev->type == EV_ABS && (ev->code == ABS_HAT0X || ev->code == ABS_HAT0Y)) {
        sync_hat(b, 0);
        return;
    }
    if (ev->type == EV_ABS && consumed_abs(ev->code)) return;
    emit(b->pass, ev->type, ev->code, ev->value);
}

// The app writes "top" or "bottom" here, then sends SIGUSR2.
static void sync_cmd(struct bridge *b) {
    char buf[16] = {0};
    FILE *f = fopen(cmd_path, "r");
    if (!f) return;
    if (!fgets(buf, sizeof(buf), f)) buf[0] = 0;
    fclose(f);
    unlink(cmd_path);
    if (!strncmp(buf, "top", 3)) b->focus = 0;
    else if (!strncmp(buf, "bottom", 6)) b->focus = 1;
    else return;
    if (b->active && b->bottom != b->focus) move_pointer(b, b->focus, 0, 0);
}

static void sync_rotation(struct bridge *b) {
    char buf[16] = {0};
    FILE *f = fopen(rotation_path, "r");
    if (!f) return;
    if (!fgets(buf, sizeof(buf), f)) buf[0] = 0;
    fclose(f);
    // "<top> <bottom>" Surface.ROTATION_* values.
    int t, m;
    if (sscanf(buf, "%d %d", &t, &m) != 2 || t < 0 || t > 3 || m < 0 || m > 3) return;
    b->rotation[0] = t;
    b->rotation[1] = m;
    if (rotate_dpad && b->active) sync_hat(b, 1);
}

// Passive (no grab). Touching a screen moves the pointer there only in mouse mode.
static void read_touch(struct bridge *b, int i) {
    struct input_event ev[32];
    ssize_t n;
    int down = 0;
    while ((n = read(b->touch_fd[i], ev, sizeof(ev))) > 0)
        for (ssize_t k = 0; k < n / (ssize_t)sizeof(*ev); k++)
            if (ev[k].type == EV_KEY && ev[k].code == BTN_TOUCH && ev[k].value == 1) down = 1;
    if (n < 0 && errno != EAGAIN) { close(b->touch_fd[i]); b->touch_fd[i] = -1; return; }
    if (!down) return;
    // "<display> <CLOCK_MONOTONIC ms>", compared against SystemClock.uptimeMillis().
    FILE *f = fopen(touch_path, "w");
    if (f) { fprintf(f, "%d %ld\n", i ? 4 : 0, now_ms()); fclose(f); chmod(touch_path, 0666); }
    b->focus = i;
    if (b->active && b->bottom != i) move_pointer(b, i, 0, 0);
}

static void read_ayn(struct bridge *b) {
    struct input_event ev[16];
    ssize_t n;
    while ((n = read(b->ayn_fd, ev, sizeof(ev))) > 0)
        for (ssize_t i = 0; i < n / (ssize_t)sizeof(*ev); i++)
            if (ev[i].type == EV_KEY && ev[i].code == AYN_SCAN && ev[i].value != 2) b->ayn = ev[i].value;
    if (n < 0 && errno != EAGAIN) { close(b->ayn_fd); b->ayn_fd = -1; b->ayn = 0; }
}

// Keyguard: release the pad so it can enter a PIN; the app signals SIGUSR2 after
// creating or removing the suspend file, so repeated signals are harmless.
static void sync_suspend(struct bridge *b) {
    int want = access(suspend_path, F_OK) == 0;
    if (want == b->suspended) return;
    // Mark suspended first so the lockscreen switch stays silent.
    b->suspended = 1;
    if (want) {
        b->resume_active = b->active;
        set_active(b, 0);
    } else if (b->resume_active) {
        set_active(b, 1);
    }
    b->suspended = want;
    fprintf(stderr, "%s\n", want ? "suspended" : "resumed");
}

// screen_focus_lock: 0 auto, 1 top, 2 bottom. Auto counts as top.
static int read_focus_lock(void) {
    FILE *p = popen("settings get system screen_focus_lock 2>/dev/null", "r");
    char buf[16] = {0};
    if (!p) return 0;
    if (!fgets(buf, sizeof(buf), p)) buf[0] = 0;
    pclose(p);
    return atoi(buf) == 2;
}

static void usage(const char *argv0) {
    fprintf(stderr,
            "usage: %s [-a] [-d deadzone 0-0.9] [-p pointer px/s] [-s scroll detents/s] [-f state file]\n"
            "       %s -k\n"
            "  -a  start in mouse mode\n"
            "  -k  send one AYN key tap and exit\n"
            "  L3 in mouse mode moves the pointer between top and bottom screens\n"
            "  SIGUSR1 toggles mouse mode; double-press Select also toggles\n"
            "  SIGUSR2 re-reads the suspend file (exists = suspended), the cmd file (top|bottom focus), and the rotation file\n",
            argv0, argv0);
}

int main(int argc, char **argv) {
    int start_active = 0, opt;
    while ((opt = getopt(argc, argv, "ard:p:s:f:hk")) != -1) {
        switch (opt) {
        case 'k': return ayn_tap();
        case 'a': start_active = 1; break;
        case 'r': rotate_dpad = 1; break;
        case 'd': deadzone = atof(optarg); break;
        case 'p': pointer_speed = atof(optarg); break;
        case 's': scroll_speed = atof(optarg); break;
        case 'f': state_path = optarg; break;
        default: usage(argv[0]); return opt == 'h' ? 0 : 2;
        }
    }
    if (deadzone < 0 || deadzone > 0.9) { usage(argv[0]); return 2; }

    // Single instance: boot start and the app can both try to launch this.
    int lock = open(lock_path, O_RDONLY | O_CREAT | O_CLOEXEC, 0666);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        fprintf(stderr, "already running\n");
        return 1;
    }
    chmod(lock_path, 0666);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sa.sa_handler = on_usr1;
    sigaction(SIGUSR1, &sa, NULL);
    sa.sa_handler = on_usr2;
    sigaction(SIGUSR2, &sa, NULL);

    struct bridge b;
    memset(&b, 0, sizeof(b));
    b.pad = b.mouse = b.pass = b.ayn_fd = -1;
    b.touch_fd[0] = b.touch_fd[1] = -1;
    write_state(0);
    // A killed daemon can leave the pointer on the bottom screen.
    move_pointer(&b, 0, 1, 1);
    b.focus = read_focus_lock();
    b.rotation[0] = b.rotation[1] = 1;
    sync_rotation(&b);
    sync_suspend(&b);

    while (!quit_flag) {
        if (b.pad < 0) {
            b.pad = find_dev(PAD_NAME, 0);
            if (b.pad < 0) { sleep(2); continue; }
            fprintf(stderr, "pad attached\n");
            memset(b.ax, 0, sizeof(b.ax));
            if (start_active && !b.suspended) set_active(&b, 1);
            else if (start_active) b.resume_active = 1;
        }

        if (b.ayn_fd < 0) b.ayn_fd = find_dev(AYN_NAME, 0x0001);
        for (int i = 0; i < 2; i++)
            if (b.touch_fd[i] < 0) b.touch_fd[i] = find_dev(touch_names[i], 0);

        int timeout = b.active ? TICK_MS : -1;
        struct pollfd fds[4] = {{.fd = b.pad, .events = POLLIN}, {.fd = b.ayn_fd, .events = POLLIN},
                                {.fd = b.touch_fd[0], .events = POLLIN}, {.fd = b.touch_fd[1], .events = POLLIN}};
        struct pollfd pfd;
        long t0 = now_ms();
        int r = poll(fds, 4, timeout);
        pfd = fds[0];
        if (fds[1].revents) read_ayn(&b);
        for (int i = 0; i < 2; i++)
            if (fds[2 + i].revents) read_touch(&b, i);

        if (suspend_flag) { suspend_flag = 0; sync_suspend(&b); sync_cmd(&b); sync_rotation(&b); }
        if (toggle_flag) { toggle_flag = 0; if (!b.suspended) set_active(&b, !b.active); }

        if (r > 0 && (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) goto lost;
        if (r > 0) {
            struct input_event ev[64];
            ssize_t n;
            while ((n = read(b.pad, ev, sizeof(ev))) > 0)
                for (ssize_t i = 0; i < n / (ssize_t)sizeof(*ev); i++) handle(&b, &ev[i]);
            if (n < 0 && errno != EAGAIN) goto lost;
        }

        reap(&b);
        if (b.want_toggle) { b.want_toggle = 0; if (!b.suspended) set_active(&b, !b.active); }

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
    if (b.ayn_fd >= 0) close(b.ayn_fd);
    unlink(state_path);
    return 0;
}
