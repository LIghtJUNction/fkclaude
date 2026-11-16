#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "ip_guard.hpp"
#include "network_audit.hpp"
#include <jni.h>
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <climits>
#include <condition_variable>
#include <cstdlib>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <netdb.h>
#include <set>
#include <sys/socket.h>
#include <sys/stat.h>
#include <thread>
#include <tuple>
#include <time.h>
#include <unistd.h>
#include <vector>
#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace fkclaude::ip_guard {
namespace {
using Identity = std::tuple<int, dev_t, ino_t>;
[[noreturn]] void enforcement_failed() {
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_ERROR, "fkclaude", "IP guard cannot revoke sockets; terminating target");
#endif
    _exit(137);
}
struct State {
    std::mutex mutex;
    std::mutex installation;
    std::condition_variable wake;
    HookFunction hook = nullptr;
    bool active = false, ready = false, stopping = false;
    int64_t leases[2] = {0, 0};
    std::multiset<Identity> probes;
    std::thread worker;
#ifdef FKCLAUDE_HOST_TEST
    void* (*resolver)(const char*) = nullptr;
#endif
};
// Process-lifetime storage: patched libc entry points may run during static teardown.
State& state() { static auto* s = new State; return *s; }
struct Probe {
    unsigned depth = 0;
    std::vector<Identity> identities;
    void clear() {
        auto& s = state();
        std::lock_guard<std::mutex> lock(s.mutex);
        for (auto id : identities) {
            auto it = s.probes.find(id);
            if (it != s.probes.end()) s.probes.erase(it);
        }
        identities.clear();
    }
    ~Probe() { clear(); }
};
thread_local Probe local_probe;
int64_t now() {
    timespec ts{};
    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) return INT64_MAX;
    return int64_t(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}
int address_family(const sockaddr* a, socklen_t len) {
    if (!a || len < sizeof(sa_family_t)) return 0;
    if (a->sa_family == AF_INET) return 4;
    if (a->sa_family == AF_INET6) {
        if (len >= sizeof(sockaddr_in6) &&
            IN6_IS_ADDR_V4MAPPED(&reinterpret_cast<const sockaddr_in6*>(a)->sin6_addr)) return 4;
        return 6;
    }
    return 0;
}
int socket_family(int fd) {
    sockaddr_storage a{};
    socklen_t n = sizeof(a);
    if (getpeername(fd, reinterpret_cast<sockaddr*>(&a), &n) == 0)
        return address_family(reinterpret_cast<sockaddr*>(&a), n);
    n = sizeof(a);
    if (getsockname(fd, reinterpret_cast<sockaddr*>(&a), &n) == 0)
        return address_family(reinterpret_cast<sockaddr*>(&a), n);
    return 0; // Preserve libc's EBADF/ENOTSOCK handling.
}
bool allowed_locked(State& s, int family) {
    return !s.active || !family || local_probe.depth ||
        s.leases[family == 4 ? 0 : 1] > now();
}
bool allow(int fd, const sockaddr* a = nullptr, socklen_t n = 0) {
    int saved = errno;
    int family = address_family(a, n);
    if (!family) family = socket_family(fd);
    if (!family) { errno = saved; return true; }
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    bool ok = allowed_locked(s, family);
    errno = ok ? saved : EACCES;
    return ok;
}
void remember_probe(int fd) {
    if (!local_probe.depth) return;
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISSOCK(st.st_mode)) return;
    Identity id{fd, st.st_dev, st.st_ino};
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    local_probe.identities.push_back(id);
    s.probes.insert(id);
}
// Caller holds mutex. Pin each file description before inspecting it, so concurrent
// close/reuse of the enumerated descriptor cannot shut down an unrelated socket.
void revoke_locked(State& s, bool v4, bool v6) {
    int saved = errno;
    DIR* dir = opendir("/proc/self/fd");
    if (!dir) enforcement_failed();
    while (true) {
        errno = 0;
        auto* entry = readdir(dir);
        if (!entry) { if (errno) enforcement_failed(); break; }
        char* end = nullptr;
        long value = strtol(entry->d_name, &end, 10);
        if (!*entry->d_name || *end || value < 0 || value > INT_MAX) continue;
        int fd = fcntl(static_cast<int>(value), F_DUPFD_CLOEXEC, 0);
        if (fd < 0) {
            if (errno != EBADF) enforcement_failed();
            continue;
        }
        struct stat st{};
        if (fstat(fd, &st) == 0 && S_ISSOCK(st.st_mode) &&
            !s.probes.count({static_cast<int>(value), st.st_dev, st.st_ino})) {
            int family = socket_family(fd);
            if ((family == 4 && v4) || (family == 6 && v6)) shutdown(fd, SHUT_RDWR);
        }
        close(fd);
    }
    closedir(dir);
    errno = saved;
}
void worker() {
    auto& s = state();
    std::unique_lock<std::mutex> lock(s.mutex);
    while (!s.stopping) {
        s.wake.wait_for(lock, std::chrono::milliseconds(100));
        if (s.stopping) break;
        const auto t = now();
        bool v4 = s.leases[0] > 0 && s.leases[0] <= t, v6 = s.leases[1] > 0 && s.leases[1] <= t;
        if (v4) s.leases[0] = 0;
        if (v6) s.leases[1] = 0;
        // Connect rechecks after completion; sweep once per expiry, not every idle tick.
        if (s.active && (v4 || v6)) revoke_locked(s, v4, v6);
    }
}
using Connect = int (*)(int, const sockaddr*, socklen_t);
using Send = ssize_t (*)(int, const void*, size_t, int);
using Sendto = ssize_t (*)(int, const void*, size_t, int, const sockaddr*, socklen_t);
using Sendmsg = ssize_t (*)(int, const msghdr*, int);
using Sendmmsg = int (*)(int, mmsghdr*, unsigned, int);
using Gai = int (*)(const char*, const char*, const addrinfo*, addrinfo**);
Connect original_connect = nullptr;
Send original_send = nullptr;
Sendto original_sendto = nullptr;
Sendmsg original_sendmsg = nullptr;
Sendmmsg original_sendmmsg = nullptr;
Gai original_gai = nullptr;
int guarded_connect(int fd, const sockaddr* a, socklen_t n) {
    network_audit::Scope scope;
    if (!allow(fd, a, n)) {
        if (scope.outer()) network_audit::observe(fd, network_audit::Operation::Connect, -1, errno, a, n, true);
        return -1;
    }
    int saved = errno;
    remember_probe(fd); // Register before connect can race with a shutdown sweep.
    errno = saved;
    int result = original_connect(fd, a, n);
    saved = errno;
    if (!local_probe.depth && !allow(fd, a, n)) {
        shutdown(fd, SHUT_RDWR);
        errno = EACCES;
        if (scope.outer()) network_audit::observe(fd, network_audit::Operation::Connect, -1, errno, a, n, true);
        return -1;
    }
    errno = saved;
    if (scope.outer()) network_audit::observe(fd, network_audit::Operation::Connect, result, errno, a, n);
    return result;
}
ssize_t guarded_send(int fd, const void* b, size_t n, int flags) {
    network_audit::Scope scope;
    if (!allow(fd)) {
        if (scope.outer()) network_audit::observe(fd, network_audit::Operation::Send, -1, errno, nullptr, 0, true);
        return -1;
    }
    auto result = original_send(fd, b, n, flags);
    if (scope.outer()) network_audit::observe(fd, network_audit::Operation::Send, result, errno);
    return result;
}
ssize_t guarded_sendto(int fd, const void* b, size_t n, int flags, const sockaddr* a, socklen_t len) {
    network_audit::Scope scope;
    if (!allow(fd, a, len)) {
        if (scope.outer()) network_audit::observe(fd, network_audit::Operation::Sendto, -1, errno, a, len, true);
        return -1;
    }
    auto result = original_sendto(fd, b, n, flags, a, len);
    if (scope.outer()) network_audit::observe(fd, network_audit::Operation::Sendto, result, errno, a, len);
    return result;
}
bool allow_message(int fd, const msghdr* m) {
    return allow(fd, m ? static_cast<const sockaddr*>(m->msg_name) : nullptr,
                 m ? m->msg_namelen : 0);
}
ssize_t guarded_sendmsg(int fd, const msghdr* m, int flags) {
    network_audit::Scope scope;
    if (!allow_message(fd, m)) {
        if (scope.outer()) network_audit::observe_message(fd, network_audit::Operation::Sendmsg, -1, errno, m, true);
        return -1;
    }
    auto result = original_sendmsg(fd, m, flags);
    if (scope.outer()) network_audit::observe_message(fd, network_audit::Operation::Sendmsg, result, errno, m);
    return result;
}
int guarded_sendmmsg(int fd, mmsghdr* m, unsigned count, int flags) {
    network_audit::Scope scope;
    if ((!m || !count) && !allow(fd)) {
        if (scope.outer()) network_audit::observe_messages(fd, -1, errno, m, count, true);
        return -1;
    }
    for (unsigned i = 0; m && i < count && i < 1024; ++i)
        if (!allow_message(fd, &m[i].msg_hdr)) {
            if (scope.outer()) network_audit::observe_messages(fd, -1, errno, m, count, true);
            return -1;
        }
    auto result = original_sendmmsg(fd, m, count, flags);
    if (scope.outer()) network_audit::observe_messages(fd, result, errno, m, count);
    return result;
}
int guarded_gai(const char* node, const char* service, const addrinfo* hints, addrinfo** out) {
    auto& s = state();
    {
        std::lock_guard<std::mutex> lock(s.mutex);
        bool ok = !s.active || local_probe.depth;
        if (!ok) {
            int f = hints ? hints->ai_family : AF_UNSPEC;
            ok = (f == AF_INET && allowed_locked(s, 4)) ||
                 (f == AF_INET6 && allowed_locked(s, 6)) ||
                 (f == AF_UNSPEC && (allowed_locked(s, 4) || allowed_locked(s, 6)));
        }
        if (!ok) { if (out) *out = nullptr; return EAI_AGAIN; }
    }
    return original_gai(node, service, hints, out);
}
void* resolve(const char* name) {
#ifdef FKCLAUDE_HOST_TEST
    if (state().resolver) return state().resolver(name);
#endif
    return dlsym(RTLD_DEFAULT, name);
}
struct Hook { const char* name; void* replacement; void** original; bool installed = false; };
Hook hooks[] = {
    {"connect", reinterpret_cast<void*>(guarded_connect), reinterpret_cast<void**>(&original_connect)},
    {"send", reinterpret_cast<void*>(guarded_send), reinterpret_cast<void**>(&original_send)},
    {"sendto", reinterpret_cast<void*>(guarded_sendto), reinterpret_cast<void**>(&original_sendto)},
    {"sendmsg", reinterpret_cast<void*>(guarded_sendmsg), reinterpret_cast<void**>(&original_sendmsg)},
    {"sendmmsg", reinterpret_cast<void*>(guarded_sendmmsg), reinterpret_cast<void**>(&original_sendmmsg)},
    {"getaddrinfo", reinterpret_cast<void*>(guarded_gai), reinterpret_cast<void**>(&original_gai)}
};
}
void set_hook_function(HookFunction hook) {
    auto& s = state(); std::lock_guard<std::mutex> lock(s.mutex); s.hook = hook;
}
bool start() {
    auto& s = state();
    std::lock_guard<std::mutex> installation(s.installation);
    HookFunction hook;
    {
        std::lock_guard<std::mutex> lock(s.mutex);
        if (s.ready) return true;
        s.active = true;
        s.leases[0] = s.leases[1] = 0;
        revoke_locked(s, true, true);
        hook = s.hook;
    }
    if (!hook) return false;
    // Resolve everything before installing; retain successful patches on partial
    // failure, keeping them blocked. Java must terminate on a false result.
    void* targets[6]{};
    for (unsigned i = 0; i < 6; ++i) {
        targets[i] = resolve(hooks[i].name);
        if (!targets[i]) return false;
    }
    for (unsigned i = 0; i < 6; ++i) {
        auto& h = hooks[i];
        if (!h.installed) {
            if (hook(targets[i], h.replacement, h.original) != 0 || !*h.original) return false;
            h.installed = true;
        }
    }
    std::lock_guard<std::mutex> lock(s.mutex);
    try { s.worker = std::thread(worker); }
    catch (...) { return false; }
    s.ready = true;
    return true;
}
void block() {
    auto& s = state(); std::lock_guard<std::mutex> lock(s.mutex);
    s.leases[0] = s.leases[1] = 0;
    if (s.active) revoke_locked(s, true, true);
}
std::int64_t permit_checked(int family, int ttl_ms, std::int64_t observation_deadline) {
    auto& s = state(); std::lock_guard<std::mutex> lock(s.mutex);
    if (!s.ready || (family != 4 && family != 6)) return 0;
    int index = family == 4 ? 0 : 1;
    auto t = now();
    s.leases[index] = ttl_ms > 0 && t != INT64_MAX && t < observation_deadline
        ? t + (ttl_ms > 10000 ? 10000 : ttl_ms) : 0;
    if (!s.leases[index]) revoke_locked(s, family == 4, family == 6);
    return s.leases[index];
}
void permit(int family, int ttl_ms) { (void) permit_checked(family, ttl_ms, INT64_MAX); }
void probe(bool enter) {
    network_audit::probe(enter);
    if (enter) { if (local_probe.depth != UINT_MAX) ++local_probe.depth; }
    else if (local_probe.depth && --local_probe.depth == 0) local_probe.clear();
}
#ifdef FKCLAUDE_HOST_TEST
void set_resolver_for_test(void* (*resolver)(const char*)) { state().resolver = resolver; }
void stop_for_test() {
    auto& s = state();
    { std::lock_guard<std::mutex> lock(s.mutex); s.stopping = true; s.wake.notify_all(); }
    if (s.worker.joinable()) s.worker.join();
    std::lock_guard<std::mutex> lock(s.mutex);
    s.stopping = false; s.ready = false; s.active = false;
    s.leases[0] = s.leases[1] = 0;
    for (auto& h : hooks) h.installed = false;
}
#endif
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_lightjunction_fkclaude_IpGuard_nativeStart(JNIEnv*, jclass) {
    return fkclaude::ip_guard::start() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_lightjunction_fkclaude_IpGuard_nativeBlock(JNIEnv*, jclass) { fkclaude::ip_guard::block(); }
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_lightjunction_fkclaude_IpGuard_nativePermit(JNIEnv*, jclass, jint f, jint ttl, jlong deadline) {
    return fkclaude::ip_guard::permit_checked(f, ttl, deadline);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_lightjunction_fkclaude_IpGuard_nativeProbe(JNIEnv*, jclass, jboolean enter) { fkclaude::ip_guard::probe(enter == JNI_TRUE); }
