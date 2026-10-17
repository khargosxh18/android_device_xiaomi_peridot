#define LOG_TAG "charging"

#include <android-base/properties.h>
#include <android/log.h>

#include <errno.h>
#include <linux/netlink.h>
#include <poll.h>
#include <signal.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <unistd.h>

#include <cctype>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <string>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kCurrentNode = "/sys/class/power_supply/battery/constant_charge_current";
constexpr const char* kLimitNode = "/sys/class/power_supply/battery/charge_control_limit";
constexpr const char* kReadCurrentNode = "/sys/class/power_supply/battery/current_now";
constexpr const char* kTempNode = "/sys/class/power_supply/battery/temp";
constexpr const char* kStatusNode = "/sys/class/power_supply/battery/status";
constexpr const char* kBypassNode = "/sys/class/qcom-battery/bypass_charging_enable";

constexpr const char* kModeProp = "persist.vendor.charge.mode";
constexpr const char* kThermaldProp = "vendor.charging.thermald";

constexpr long mA(long v) { return v * 1000; }

constexpr int kTickSec = 5;
constexpr int kSlots = 6;
constexpr int kLimitEverySlots = 3;
constexpr int kModes = 3;

constexpr long kCeilingDeciC = 480;
constexpr long kResumeDeciC = 460;

constexpr long kProfiles[kModes][kSlots] = {
    {mA(3000), mA(3000), mA(3000), mA(3000), mA(3000), mA(3000)},
    {mA(6000), mA(6000), mA(6000), mA(6000), mA(6000), mA(3000)},
    {mA(12500), mA(12500), mA(12500), mA(12500), mA(12500), mA(3000)},
};

volatile sig_atomic_t g_stop = 0;

void onSignal(int) { g_stop = 1; }

bool readStr(const char* path, std::string* out) {
    std::ifstream f(path);
    if (!f.is_open() || !std::getline(f, *out)) return false;
    while (!out->empty() && isspace(static_cast<unsigned char>(out->back()))) out->pop_back();
    return true;
}

bool readLong(const char* path, long* out) {
    std::string s;
    if (!readStr(path, &s) || s.empty()) return false;
    char* end = nullptr;
    errno = 0;
    long v = strtol(s.c_str(), &end, 10);
    if (errno != 0 || end == s.c_str()) return false;
    *out = v;
    return true;
}

bool writeLong(const char* path, long value) {
    std::ofstream f(path);
    if (!f.is_open()) return false;
    f << value;
    f.flush();
    return !f.fail();
}

struct Node {
    const char* path;
    long last_target = LONG_MIN;
    long last_readback = LONG_MIN;

    void forget() { last_target = last_readback = LONG_MIN; }

    void set(long target) {
        long cur;
        if (readLong(path, &cur) && (cur == target || (target == last_target && cur == last_readback))) {
            return;
        }
        if (!writeLong(path, target)) {
            LOGW("write %ld to %s failed: %s", target, path, strerror(errno));
            return;
        }
        long back = target;
        readLong(path, &back);
        last_target = target;
        last_readback = back;
        if (back != target) {
            LOGW("%s clamped: wrote %ld, reads %ld", path, target, back);
        } else {
            LOGI("%s = %ld", path, target);
        }
    }
};

class Governor {
  public:
    void tick() {
        long temp = kCeilingDeciC;
        readLong(kTempNode, &temp);

        if (paused_) {
            setThermald(true);
            if (temp > kResumeDeciC) return;
            paused_ = false;
            LOGI("%.1fC, resuming", temp / 10.0);
        } else if (temp >= kCeilingDeciC) {
            paused_ = true;
            LOGW("%.1fC, pausing and handing over to mi_thermald", temp / 10.0);
            setThermald(true);
            return;
        }

        if (!setThermald(false)) return;

        int mode = currentMode();
        if (mode != last_mode_) {
            LOGI("mode %d", mode);
            last_mode_ = mode;
            slot_ = 0;
        }

        if (slot_ % kLimitEverySlots == 0) limit_.set(0);
        current_.set(kProfiles[mode][slot_]);

        long now = 0;
        readLong(kReadCurrentNode, &now);
        LOGD("slot %d mode %d %.1fC current_now %ld", slot_, mode, temp / 10.0, now);

        slot_ = (slot_ + 1) % kSlots;
    }

    void release() {
        paused_ = false;
        slot_ = 0;
        last_mode_ = -1;
        current_.forget();
        limit_.forget();
        setThermald(true);
    }

  private:
    static int currentMode() {
        long m = strtol(android::base::GetProperty(kModeProp, "0").c_str(), nullptr, 10);
        return (m >= 0 && m < kModes) ? static_cast<int>(m) : 0;
    }

    bool setThermald(bool run) {
        if (thermald_running_ == (run ? 1 : 0)) return true;
        if (!android::base::SetProperty(kThermaldProp, run ? "start" : "stop")) {
            LOGW("failed to set %s", kThermaldProp);
            return false;
        }
        thermald_running_ = run ? 1 : 0;
        LOGI("mi_thermald %s", run ? "start" : "stop");
        if (!run) usleep(300 * 1000);
        return true;
    }

    Node current_{kCurrentNode};
    Node limit_{kLimitNode};
    int slot_ = 0;
    int last_mode_ = -1;
    int thermald_running_ = -1;
    bool paused_ = false;
};

bool bypassActive() {
    static bool last = false;
    static bool warned = false;
    std::string s;
    errno = 0;
    bool known = readStr(kBypassNode, &s);
    if (!known && errno != 0 && errno != ENOENT && !warned) {
        LOGW("bypass node unreadable: %s", strerror(errno));
        warned = true;
    }
    bool on = known && s != "0";
    if (on != last) {
        LOGI("bypass %s (%s)", on ? "on" : "off", s.c_str());
        last = on;
    }
    return on;
}

bool isCharging() {
    std::string s;
    return readStr(kStatusNode, &s) && s == "Charging" && !bypassActive();
}

int openUeventSocket() {
    int fd = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_KOBJECT_UEVENT);
    if (fd < 0) return -1;
    sockaddr_nl addr = {};
    addr.nl_family = AF_NETLINK;
    addr.nl_groups = 1;
    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

bool drainUevents(int fd) {
    char buf[2048];
    bool seen = false;
    ssize_t n;
    while ((n = recv(fd, buf, sizeof(buf) - 1, 0)) > 0) {
        buf[n] = '\0';
        for (ssize_t i = 0; i < n; i += static_cast<ssize_t>(strlen(buf + i)) + 1) {
            if (strcmp(buf + i, "SUBSYSTEM=power_supply") == 0) seen = true;
        }
    }
    return seen;
}

void armTimer(int fd, bool on) {
    itimerspec its = {};
    if (on) its.it_value.tv_sec = its.it_interval.tv_sec = kTickSec;
    timerfd_settime(fd, 0, &its, nullptr);
}

}

int main() {
    struct sigaction sa = {};
    sa.sa_handler = onSignal;
    sigaction(SIGTERM, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);

    int uevent_fd = openUeventSocket();
    if (uevent_fd < 0) {
        LOGW("uevent socket: %s", strerror(errno));
        return 1;
    }

    int timer_fd = timerfd_create(CLOCK_BOOTTIME_ALARM, TFD_CLOEXEC | TFD_NONBLOCK);
    if (timer_fd < 0) timer_fd = timerfd_create(CLOCK_BOOTTIME, TFD_CLOEXEC | TFD_NONBLOCK);
    if (timer_fd < 0) {
        LOGW("timerfd: %s", strerror(errno));
        return 1;
    }

    Governor gov;
    gov.release();

    bool active = false;
    bool recheck = true;
    bool tick_due = false;

    while (!g_stop) {
        if (recheck) {
            bool charging = isCharging();
            if (charging && !active) {
                active = true;
                LOGI("charging started");
                armTimer(timer_fd, true);
                gov.tick();
            } else if (!charging && active) {
                active = false;
                LOGI("charging stopped");
                armTimer(timer_fd, false);
                gov.release();
            }
        }
        if (active && tick_due) gov.tick();
        recheck = tick_due = false;

        pollfd fds[2] = {{uevent_fd, POLLIN, 0}, {timer_fd, POLLIN, 0}};
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR) continue;
            LOGW("poll: %s", strerror(errno));
            break;
        }
        if (fds[0].revents & POLLIN) recheck = drainUevents(uevent_fd);
        if (fds[1].revents & POLLIN) {
            uint64_t expirations;
            if (read(timer_fd, &expirations, sizeof(expirations)) > 0) recheck = tick_due = true;
        }
    }

    gov.release();
    return 0;
}
