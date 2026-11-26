// SPDX-License-Identifier: Apache-2.0
// thor-perf: auto-cpufreq style adaptive Odin performance profile daemon for AYN Thor.
// Odin's performance_mode (0=Normal, 1=Performance, 2=High Performance) only raises minimum
// CPU/GPU/DDR clock floors while keeping full peak clocks (3.19 GHz / 680 MHz) available in
// every mode. This daemon scales up rapidly under real CPU/single-core/GPU demand and steps
// back down after sustained low utilization so idle/light use saves battery without throttling.

#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define GPU_BUSY_PATH "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
#define STAT_PATH "/proc/stat"
#define MAX_CORES 16
#define TICK_S 2
#define SUSPEND_TICK_S 30
#define LOG_TICKS 30

// Promote quickly on sustained load (4 s) so brief background spikes stay in Normal;
// demote patiently (20 s hold) so games/emulators never stutter.
#define UP_PERF_CPU 30
#define UP_PERF_CORE 65
#define UP_PERF_GPU 30
#define UP_PERF_HOLD_TICKS 2
#define UP_HIGH_CPU 60
#define UP_HIGH_CORE 85
#define UP_HIGH_GPU 65
#define UP_HIGH_HOLD_TICKS 2

#define DOWN_PERF_CPU 40
#define DOWN_PERF_CORE 65
#define DOWN_PERF_GPU 45
#define DOWN_NORM_CPU 20
#define DOWN_NORM_CORE 40
#define DOWN_NORM_GPU 18
#define DOWN_HOLD_TICKS 10

static const char *state_path = "/data/local/tmp/thorutil-perf.state";
static const char *lock_path = "/data/local/tmp/thorutil-perf.lock";
static const char *suspend_path = "/data/local/tmp/thorutil-perf.suspend";
static const char *change_path = "/data/local/tmp/thorutil-perf.change";

static int stat_fd = -1;
static int gpu_fd = -1;
static volatile sig_atomic_t quit;

static void on_signal(int s) {
    (void)s;
    quit = 1;
}

static void on_wake(int s) {
    (void)s;
}

struct cpu_sample {
    unsigned long long busy;
    unsigned long long total;
};

static int read_cpu(struct cpu_sample *agg, struct cpu_sample *cores, int *ncores) {
    if (stat_fd < 0) return -1;
    char buf[4096];
    ssize_t nr = pread(stat_fd, buf, sizeof buf - 1, 0);
    if (nr <= 0) return -1;
    buf[nr] = 0;

    int n = 0;
    int got_agg = 0;
    char *p = buf;
    while (*p) {
        char *nl = strchr(p, '\n');
        if (nl) *nl = 0;
        if (strncmp(p, "cpu", 3) != 0) break;
        unsigned long long user = 0, nice = 0, sys = 0, idle = 0, iow = 0, irq = 0, sirq = 0, steal = 0;
        int is_agg = (p[3] == ' ');
        char tag[16];
        int matched = sscanf(p, "%15s %llu %llu %llu %llu %llu %llu %llu %llu",
                             tag, &user, &nice, &sys, &idle, &iow, &irq, &sirq, &steal);
        if (matched >= 5) {
            unsigned long long idle_all = idle + iow;
            unsigned long long busy = user + nice + sys + irq + sirq + steal;
            unsigned long long total = busy + idle_all;
            if (is_agg) {
                agg->busy = busy;
                agg->total = total;
                got_agg = 1;
            } else if (n < MAX_CORES) {
                cores[n].busy = busy;
                cores[n].total = total;
                n++;
            }
        }
        if (!nl) break;
        p = nl + 1;
    }
    *ncores = n;
    return got_agg ? 0 : -1;
}

static int pct_delta(struct cpu_sample prev, struct cpu_sample cur) {
    unsigned long long dt = cur.total > prev.total ? cur.total - prev.total : 0;
    if (!dt) return 0;
    unsigned long long db = cur.busy > prev.busy ? cur.busy - prev.busy : 0;
    int p = (int)((db * 100ULL + dt / 2) / dt);
    return p > 100 ? 100 : p;
}

static int read_gpu_busy(void) {
    if (gpu_fd < 0) return 0;
    char buf[32];
    ssize_t n = pread(gpu_fd, buf, sizeof buf - 1, 0);
    if (n <= 0) return 0;
    buf[n] = 0;
    int v = atoi(buf);
    return v < 0 ? 0 : v > 100 ? 100 : v;
}

static void mark_change(void) {
    int fd = open(change_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    if (fd >= 0) {
        char buf[32];
        int n = snprintf(buf, sizeof buf, "%ld\n", (long)time(NULL));
        if (n > 0) (void)!write(fd, buf, (size_t)n);
        close(fd);
        chmod(change_path, 0666);
    }
}

static void apply_mode(int mode) {
    mark_change();
    pid_t pid = fork();
    if (pid == 0) {
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) { dup2(nul, 1); dup2(nul, 2); }
        char cmd[128];
        snprintf(cmd, sizeof cmd,
                 "settings put system performance_mode %d; setprop persist.vendor.debug.mode %d 2>/dev/null",
                 mode, mode);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    if (pid > 0) waitpid(pid, NULL, 0);
    // Re-stamp after settings put finishes so FanTile's bounce observer sees the full 3s window.
    mark_change();
}

// State format: pid mode cpu_pct max_core_pct gpu_pct
// Uses raw open/write to avoid musl stdio TIOCGWINSZ ioctl SELinux audit spam.
static void write_state(int mode, int cpu_pct, int core_pct, int gpu_pct) {
    char tmp[128], buf[64];
    snprintf(tmp, sizeof tmp, "%s.tmp", state_path);
    int len = snprintf(buf, sizeof buf, "%d %d %d %d %d\n", getpid(), mode, cpu_pct, core_pct, gpu_pct);
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

int main(void) {
    umask(0);
    int lock = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        fprintf(stderr, "already running\n");
        return 1;
    }

    stat_fd = open(STAT_PATH, O_RDONLY | O_CLOEXEC);
    gpu_fd = open(GPU_BUSY_PATH, O_RDONLY | O_CLOEXEC);

    struct sigaction sa = {0};
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sa.sa_handler = on_wake;
    sigaction(SIGUSR1, &sa, NULL);

    struct cpu_sample prev_agg = {0}, prev_cores[MAX_CORES] = {{0}};
    int prev_n = 0;
    read_cpu(&prev_agg, prev_cores, &prev_n);

    int mode = -1;
    int perf_ticks = 0;
    int high_ticks = 0;
    int cool_ticks = 0;
    int was_suspended = 0;
    unsigned long tick = 0;

    write_state(0, 0, 0, 0);
    fprintf(stderr, "perf: adaptive loop started (%d cores)\n", prev_n);

    while (!quit) {
        int suspended = access(suspend_path, F_OK) == 0;
        if (suspended != was_suspended) {
            was_suspended = suspended;
            perf_ticks = 0;
            high_ticks = 0;
            cool_ticks = 0;
            fprintf(stderr, "perf: %s\n", suspended ? "suspended" : "resumed");
            if (suspended) {
                if (mode != 0) {
                    mode = 0;
                    apply_mode(0);
                }
                write_state(0, 0, 0, 0);
            } else {
                // Re-seed baseline after sleep so the first awake delta covers only awake load.
                read_cpu(&prev_agg, prev_cores, &prev_n);
            }
        }
        if (suspended) {
            // Sleep deeply while screen-off; SIGUSR1/SIGTERM wakes immediately.
            struct timespec ts = {SUSPEND_TICK_S, 0};
            nanosleep(&ts, NULL);
            continue;
        }

        struct timespec ts = {TICK_S, 0};
        nanosleep(&ts, NULL);
        if (quit) break;
        if (access(suspend_path, F_OK) == 0) continue;

        struct cpu_sample cur_agg = {0}, cur_cores[MAX_CORES] = {{0}};
        int cur_n = 0;
        int cpu_pct = 0, core_pct = 0;
        if (read_cpu(&cur_agg, cur_cores, &cur_n) == 0) {
            cpu_pct = pct_delta(prev_agg, cur_agg);
            int n = cur_n < prev_n ? cur_n : prev_n;
            for (int i = 0; i < n; i++) {
                int p = pct_delta(prev_cores[i], cur_cores[i]);
                if (p > core_pct) core_pct = p;
            }
            prev_agg = cur_agg;
            memcpy(prev_cores, cur_cores, sizeof cur_cores);
            prev_n = cur_n;
        }
        int gpu_pct = read_gpu_busy();

        int want_high = (gpu_pct >= UP_HIGH_GPU || cpu_pct >= UP_HIGH_CPU || core_pct >= UP_HIGH_CORE);
        int want_perf = (gpu_pct >= UP_PERF_GPU || cpu_pct >= UP_PERF_CPU || core_pct >= UP_PERF_CORE);

        int next = mode < 0 ? 0 : mode;
        if (want_high) {
            cool_ticks = 0;
            perf_ticks++;
            if (++high_ticks >= UP_HIGH_HOLD_TICKS) next = 2;
            else if (next < 1) next = 1;
        } else if (want_perf) {
            high_ticks = 0;
            if (next < 1) {
                cool_ticks = 0;
                if (++perf_ticks >= UP_PERF_HOLD_TICKS) next = 1;
            } else if (next == 2) {
                if (gpu_pct < DOWN_PERF_GPU && cpu_pct < DOWN_PERF_CPU && core_pct < DOWN_PERF_CORE) {
                    if (++cool_ticks >= DOWN_HOLD_TICKS) { next = 1; cool_ticks = 0; }
                } else {
                    cool_ticks = 0;
                }
            }
        } else {
            perf_ticks = 0;
            high_ticks = 0;
            if (next == 2) {
                if (gpu_pct < DOWN_PERF_GPU && cpu_pct < DOWN_PERF_CPU && core_pct < DOWN_PERF_CORE) {
                    if (++cool_ticks >= DOWN_HOLD_TICKS) { next = 1; cool_ticks = 0; }
                } else {
                    cool_ticks = 0;
                }
            } else if (next == 1) {
                if (gpu_pct < DOWN_NORM_GPU && cpu_pct < DOWN_NORM_CPU && core_pct < DOWN_NORM_CORE) {
                    if (++cool_ticks >= DOWN_HOLD_TICKS) { next = 0; cool_ticks = 0; }
                } else {
                    cool_ticks = 0;
                }
            }
        }

        if (next != mode) {
            fprintf(stderr, "perf: mode %d -> %d (cpu=%d%% core=%d%% gpu=%d%%)\n",
                    mode, next, cpu_pct, core_pct, gpu_pct);
            mode = next;
            apply_mode(mode);
        } else if (tick % LOG_TICKS == 0) {
            fprintf(stderr, "perf: mode %d (cpu=%d%% core=%d%% gpu=%d%%)\n",
                    mode, cpu_pct, core_pct, gpu_pct);
        }
        write_state(mode, cpu_pct, core_pct, gpu_pct);
        tick++;
    }

    unlink(state_path);
    return 0;
}
