// thor-fan: smoothed fan curve for the AYN Thor.
// Needs Odin fan_mode 6 (custom) so the vendor loop leaves gpio5_pwm2/duty alone.
// Drives the curve from the board soak sensor (xo-therm) so short CPU bursts don't
// step the fan, with a sustained junction guard (hottest cpu/gpu >= guard_c for
// guard_s) forcing 100% if throttling fails to hold die temperature.

#include <dirent.h>
#include <fcntl.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define DUTY_PATH "/sys/class/gpio5_pwm2/duty"
#define PERIOD_PATH "/sys/class/gpio5_pwm2/period"
#define STATE_PATH "/sys/class/gpio5_pwm2/state"
#define USB_ONLINE_PATH "/sys/class/power_supply/usb/online"
#define BAT_STATUS_PATH "/sys/class/power_supply/battery/status"
#define THERMAL_DIR "/sys/class/thermal"
#define SOAK_TYPE "xo-therm"
#define MAX_ZONES 64
#define MAX_POINTS 8
#define TICK_S 1
#define SUSPEND_TICK_S 30
#define LOG_TICKS 30
#define ZERO_HOLD_TICKS 10
#define ZERO_JUNCT_OFF_C 75.0
#define ZERO_JUNCT_ON_C 80.0

static const char *state_path = "/data/local/tmp/thorutil-fan.state";
static const char *lock_path = "/data/local/tmp/thorutil-fan.lock";
// Present while Odin owns the fan (screen off, or a non-Smart profile picked by the user).
static const char *suspend_path = "/data/local/tmp/thorutil-fan.suspend";

static double tau = 15.0;
static double hyst = 2.5;
static double slew_up = 1.5;
static double slew_down = 0.5;
static double guard_c = 98.0;
static int guard_s = 20;
// 0% fan on battery: disabled when zero_off_c <= 0.
static double zero_off_c = 0.0;
static double zero_on_c = 0.0;

static int soak_fd = -1;
static int pwm_state_fd = -1;
static int pwm_duty_fd = -1;
static int usb_fd = -1;
static int bat_fd = -1;
static int nzones;
static int zone_fd[MAX_ZONES];
static int npoints;
static double pt_temp[MAX_POINTS], pt_pct[MAX_POINTS];
static volatile sig_atomic_t quit;

static void on_signal(int s) {
    (void)s;
    quit = 1;
}

static void on_wake(int s) {
    (void)s;
}

static long read_long(int fd) {
    if (fd < 0) return -1;
    char buf[32];
    ssize_t n = pread(fd, buf, sizeof buf - 1, 0);
    if (n <= 0) return -1;
    buf[n] = 0;
    return strtol(buf, NULL, 10);
}

static long read_path(const char *p) {
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    long v = read_long(fd);
    close(fd);
    return v;
}

static int write_path(const char *p, long v) {
    int fd = open(p, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char buf[32];
    int n = snprintf(buf, sizeof buf, "%ld\n", v);
    int ok = write(fd, buf, (size_t)n) == n ? 0 : -1;
    close(fd);
    return ok;
}

static void open_zones(void) {
    DIR *d = opendir(THERMAL_DIR);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "thermal_zone", 12)) continue;
        char p[256], type[64] = {0};
        snprintf(p, sizeof p, THERMAL_DIR "/%s/type", e->d_name);
        int tfd = open(p, O_RDONLY | O_CLOEXEC);
        if (tfd < 0) continue;
        ssize_t n = read(tfd, type, sizeof type - 1);
        close(tfd);
        if (n <= 0) continue;
        type[n] = 0;
        while (n > 0 && (type[n - 1] == '\n' || type[n - 1] == '\r')) type[--n] = 0;

        int is_soak = strcmp(type, SOAK_TYPE) == 0;
        int is_junct = strstr(type, "cpu") != NULL || strstr(type, "gpu") != NULL;
        if (!is_soak && !is_junct) continue;

        snprintf(p, sizeof p, THERMAL_DIR "/%s/temp", e->d_name);
        int fd = open(p, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        if (is_soak && soak_fd < 0) soak_fd = fd;
        else if (is_junct && nzones < MAX_ZONES) zone_fd[nzones++] = fd;
        else close(fd);
    }
    closedir(d);
}

static double read_soak(void) {
    if (soak_fd < 0) return NAN;
    long v = read_long(soak_fd);
    double c = v / 1000.0;
    return (v < 0 || c > 150.0) ? NAN : c;
}

// Hottest valid cpu/gpu zone in °C, or NAN.
static double hottest(void) {
    double best = NAN;
    for (int i = 0; i < nzones; i++) {
        long v = read_long(zone_fd[i]);
        double c = v / 1000.0;
        if (v < 0 || c > 150.0) continue;
        if (isnan(best) || c > best) best = c;
    }
    return best;
}

// "46:20,52:35,58:60,64:100" -> sorted points.
static int parse_curve(const char *s) {
    npoints = 0;
    while (*s && npoints < MAX_POINTS) {
        char *end;
        double t = strtod(s, &end);
        if (*end != ':') return -1;
        double p = strtod(end + 1, &end);
        if (p < 0 || p > 100) return -1;
        pt_temp[npoints] = t;
        pt_pct[npoints] = p;
        npoints++;
        if (*end == ',') end++;
        else if (*end) return -1;
        s = end;
    }
    for (int i = 1; i < npoints; i++)
        for (int j = i; j > 0 && pt_temp[j] < pt_temp[j - 1]; j--) {
            double t = pt_temp[j]; pt_temp[j] = pt_temp[j - 1]; pt_temp[j - 1] = t;
            double p = pt_pct[j]; pt_pct[j] = pt_pct[j - 1]; pt_pct[j - 1] = p;
        }
    return npoints ? 0 : -1;
}

static double curve(double t) {
    if (t <= pt_temp[0]) return pt_pct[0];
    for (int i = 1; i < npoints; i++)
        if (t <= pt_temp[i])
            return pt_pct[i - 1] + (t - pt_temp[i - 1]) / (pt_temp[i] - pt_temp[i - 1]) * (pt_pct[i] - pt_pct[i - 1]);
    return pt_pct[npoints - 1];
}

static int is_plugged(void) {
    if (read_long(usb_fd) == 1) return 1;
    if (bat_fd < 0) return 1; // fail-safe: treat unreadable battery status as plugged so fan runs
    char buf[32];
    ssize_t n = pread(bat_fd, buf, sizeof buf - 1, 0);
    if (n <= 0) return 1;
    buf[n] = 0;
    return strncmp(buf, "Discharging", 11) != 0;
}

// State format: pid pct soak_c rpm junct_c guard_active zero_stopped
// rpm is always 0 here because reading gpio5_pwm2/speed busy-spins ~100ms in kernel;
// the UI reads RPM on demand only while open. Uses raw open/write to avoid stdio ioctl spam.
static void write_state(int pct, double soak, double junct, int guarded, int zero_stopped) {
    char tmp[128], buf[96];
    snprintf(tmp, sizeof tmp, "%s.tmp", state_path);
    int len = snprintf(buf, sizeof buf, "%d %d %.1f 0 %.1f %d %d\n",
                       getpid(), pct, isnan(soak) ? 0.0 : soak,
                       isnan(junct) ? 0.0 : junct, guarded, zero_stopped);
    if (len <= 0) return;
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    if (fd < 0) return;
    ssize_t wr = write(fd, buf, (size_t)len);
    close(fd);
    if (wr == len) {
        chmod(tmp, 0666);
        rename(tmp, state_path);
    } else {
        unlink(tmp);
    }
}

static void usage(void) {
    fprintf(stderr, "usage: thor-fan -c T:P,T:P... [-t tau_s] [-h hyst_c] [-u up_pct_s] [-D down_pct_s] [-g guard_c] [-G guard_s] [-z zero_off_c] [-Z zero_on_c]\n");
}

int main(int argc, char **argv) {
    const char *spec = "48:20,54:32,60:50,66:75,72:100";
    int o;
    while ((o = getopt(argc, argv, "c:t:h:u:D:g:G:z:Z:")) != -1) {
        switch (o) {
        case 'c': spec = optarg; break;
        case 't': tau = atof(optarg); break;
        case 'h': hyst = atof(optarg); break;
        case 'u': slew_up = atof(optarg); break;
        case 'D': slew_down = atof(optarg); break;
        case 'g': guard_c = atof(optarg); break;
        case 'G': guard_s = atoi(optarg); break;
        case 'z': zero_off_c = atof(optarg); break;
        case 'Z': zero_on_c = atof(optarg); break;
        default: usage(); return 2;
        }
    }
    if (zero_off_c > 0.0 && zero_on_c <= zero_off_c) zero_on_c = zero_off_c + 4.0;
    if (parse_curve(spec) < 0) {
        fprintf(stderr, "bad curve: %s\n", spec);
        return 2;
    }
    // Files must stay writable by shell when a root Shizuku started us.
    umask(0);
    int lock = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        fprintf(stderr, "already running\n");
        return 1;
    }
    long period = read_path(PERIOD_PATH);
    if (period <= 0) period = 50000;
    pwm_state_fd = open(STATE_PATH, O_RDONLY | O_CLOEXEC);
    pwm_duty_fd = open(DUTY_PATH, O_RDONLY | O_CLOEXEC);
    usb_fd = open(USB_ONLINE_PATH, O_RDONLY | O_CLOEXEC);
    bat_fd = open(BAT_STATUS_PATH, O_RDONLY | O_CLOEXEC);
    open_zones();
    if (soak_fd < 0 && !nzones) {
        fprintf(stderr, "no thermal zones\n");
        return 1;
    }

    struct sigaction sa = {0};
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sa.sa_handler = on_wake;
    sigaction(SIGUSR1, &sa, NULL);

    double alpha = (double)TICK_S / (tau + (double)TICK_S);
    double smooth = NAN, held = NAN, applied = pt_pct[0];
    long written = -1;
    int over_ticks = 0;
    int cool_ticks = 0;
    int zero_stopped = 0;
    int was_suspended = 0;
    unsigned long tick = 0;
    fprintf(stderr, "fan: soak=%s %d junct zones, %d points, period %ld, tau %.0fs hyst %.1fC slew +%.1f/-%.1f %%/s guard %.0fC/%ds zero %.1f/%.1fC\n",
            soak_fd >= 0 ? SOAK_TYPE : "fallback", nzones, npoints, period, tau, hyst, slew_up, slew_down, guard_c, guard_s, zero_off_c, zero_on_c);

    // Write initial state immediately so start/watchdog checks see our pid even if launched suspended.
    write_state(0, 0.0, 0.0, 0, 0);

    while (!quit) {
        int suspended = access(suspend_path, F_OK) == 0;
        if (suspended != was_suspended) {
            fprintf(stderr, "fan: %s\n", suspended ? "suspended" : "resumed");
            was_suspended = suspended;
            written = -1;
            over_ticks = 0;
            cool_ticks = 0;
            zero_stopped = 0;
            if (suspended) {
                write_state(0, isnan(smooth) ? 0.0 : smooth, 0.0, 0, 0);
            } else {
                // Drop stale pre-sleep thermal history and ramp cleanly from the floor.
                smooth = NAN;
                held = NAN;
                applied = pt_pct[0];
            }
        }
        if (suspended) {
            // Sleep deeply while screen-off or yielded; SIGUSR1/SIGTERM wakes immediately.
            struct timespec ts = {SUSPEND_TICK_S, 0};
            nanosleep(&ts, NULL);
            continue;
        }

        double junct = hottest();
        double raw = soak_fd >= 0 ? read_soak() : junct;
        if (!isnan(raw)) {
            smooth = isnan(smooth) ? raw : smooth + alpha * (raw - smooth);
            // Rising follows at once; falling waits until hyst degrees below the hold point.
            if (isnan(held) || smooth > held) held = smooth;
            else if (smooth < held - hyst) held = smooth + hyst;

            if (!isnan(junct) && junct >= guard_c) over_ticks++;
            else if (isnan(junct) || junct < guard_c - 3.0) over_ticks = 0;
            int guarded = guard_s <= 0 ? (over_ticks > 0) : (over_ticks >= guard_s);

            int plugged = zero_off_c > 0.0 ? is_plugged() : 1;
            if (zero_off_c > 0.0) {
                if (zero_stopped) {
                    if (plugged || guarded || smooth >= zero_on_c ||
                        (!isnan(junct) && junct >= ZERO_JUNCT_ON_C)) {
                        write_path(STATE_PATH, 1);
                        zero_stopped = 0;
                        cool_ticks = 0;
                        applied = pt_pct[0];
                        written = -1;
                        fprintf(stderr, "fan: zero exit (soak %.1fC junct %.1fC plugged=%d)\n",
                                smooth, isnan(junct) ? -1.0 : junct, plugged);
                    }
                } else if (!plugged && !guarded && smooth <= zero_off_c &&
                           (isnan(junct) || junct < ZERO_JUNCT_OFF_C)) {
                    if (++cool_ticks >= ZERO_HOLD_TICKS && read_long(pwm_state_fd) == 1) {
                        write_path(DUTY_PATH, 0);
                        if (write_path(STATE_PATH, 0) == 0) {
                            zero_stopped = 1;
                            applied = 0.0;
                            written = 0;
                            fprintf(stderr, "fan: zero enter (soak %.1fC junct %.1fC)\n",
                                    smooth, isnan(junct) ? -1.0 : junct);
                        }
                    }
                } else {
                    cool_ticks = 0;
                }
            }

            if (zero_stopped) {
                // Reassert state=0 if Odin re-enables the rail on app switch while cool on battery.
                if (read_long(pwm_state_fd) == 1) {
                    write_path(DUTY_PATH, 0);
                    write_path(STATE_PATH, 0);
                }
                if (tick % 2 == 0) write_state(0, smooth, junct, 0, 1);
                if (tick % LOG_TICKS == 0)
                    fprintf(stderr, "fan: soak %.1fC junct %.1fC -> 0%% (battery)\n",
                            smooth, isnan(junct) ? -1.0 : junct);
            } else {
                double want = guarded ? 100.0 : curve(held);
                if (guarded) applied = 100.0;
                else {
                    double up = slew_up * TICK_S, down = slew_down * TICK_S;
                    double d = want - applied;
                    applied += d > up ? up : d < -down ? -down : d;
                }
                int pct = (int)lround(applied);
                long duty = period * pct / 100;
                int reassert = 0;
                // Only drive duty while Odin has the fan rail enabled; check every tick so any
                // external overwrite is corrected immediately instead of lingering for 10 s.
                if (read_long(pwm_state_fd) == 1) {
                    long cur = read_long(pwm_duty_fd);
                    reassert = (duty == written && cur != duty);
                    if (duty != written || cur != duty) {
                        if (write_path(DUTY_PATH, duty) == 0) written = duty;
                    }
                } else {
                    written = -1;
                }
                if (tick % 2 == 0 || reassert)
                    write_state(pct, smooth, junct, guarded, 0);
                if (tick % LOG_TICKS == 0 || reassert)
                    fprintf(stderr, "fan: soak %.1fC (s %.1f h %.1f) junct %.1fC -> %d%%%s%s\n",
                            raw, smooth, held, isnan(junct) ? -1.0 : junct, pct,
                            guarded ? " GUARD" : "", reassert ? " reassert" : "");
            }
        }
        tick++;
        struct timespec ts = {TICK_S, 0};
        nanosleep(&ts, NULL);
    }
    if (zero_stopped) write_path(STATE_PATH, 1);
    unlink(state_path);
    return 0;
}
