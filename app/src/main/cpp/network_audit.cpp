#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "network_audit.hpp"
#include <jni.h>
#include <arpa/inet.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

namespace fkclaude::network_audit {
namespace {
struct State {
    std::mutex queue_mutex;
    std::mutex installation;
    std::atomic<bool> enabled{false};
    std::atomic<std::uint64_t> dropped{0};
    std::array<Event, QUEUE_CAPACITY> events{};
    std::size_t head = 0, size = 0;
    HookFunction hook = nullptr;
    int mask = 0;
    bool installed = false, guard_mode = false;
#ifdef FKCLAUDE_HOST_TEST
    void* (*resolver)(const char*) = nullptr;
#endif
};
// Inline hooks can execute during process teardown.
State& state() { static auto* value = new State; return *value; }
thread_local unsigned call_depth = 0;
thread_local unsigned probe_depth = 0;
thread_local bool observing = false;
struct PreserveErrno {
    int value = errno;
    ~PreserveErrno() { errno = value; }
};
std::int64_t clock_ms() noexcept {
    timespec now{};
    return clock_gettime(CLOCK_BOOTTIME, &now) == 0
        ? std::int64_t(now.tv_sec) * 1000 + now.tv_nsec / 1000000 : 0;
}
bool endpoint(Event& event, const sockaddr* source, socklen_t size) noexcept {
    if (!source || size < sizeof(sa_family_t)) return false;
    sa_family_t family;
    std::memcpy(&family, source, sizeof(family));
    if (family == AF_INET && size >= sizeof(sockaddr_in)) {
        sockaddr_in address{};
        std::memcpy(&address, source, sizeof(address));
        event.family = 4;
        event.port = ntohs(address.sin_port);
        if (!inet_ntop(AF_INET, &address.sin_addr, event.address, sizeof(event.address)))
            event.address[0] = 0;
        return true;
    }
    if (family == AF_INET6 && size >= sizeof(sockaddr_in6)) {
        sockaddr_in6 address{};
        std::memcpy(&address, source, sizeof(address));
        event.family = 6;
        event.port = ntohs(address.sin6_port);
        if (!inet_ntop(AF_INET6, &address.sin6_addr, event.address, sizeof(event.address)))
            event.address[0] = 0;
        return true;
    }
    return false;
}
bool socket_metadata(int fd, Event& event, const sockaddr* address, socklen_t length) noexcept {
    int type = 0;
    socklen_t type_size = sizeof(type);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_size) != 0) return false;
    sockaddr_storage peer{};
    socklen_t size = sizeof(peer);
    // The caller supplies a destination only after the kernel accepted it.
    bool known = endpoint(event, address, length);
    if (!known && getpeername(fd, reinterpret_cast<sockaddr*>(&peer), &size) == 0)
        known = endpoint(event, reinterpret_cast<sockaddr*>(&peer), size);
    if (!known) {
        size = sizeof(peer);
        if (getsockname(fd, reinterpret_cast<sockaddr*>(&peer), &size) != 0) return false;
        event.family = peer.ss_family == AF_INET ? 4 : peer.ss_family == AF_INET6 ? 6 : 0;
        // Never record the local/source address.
    }
    if (event.family != 4 && event.family != 6) return false;
    event.socket_type = type;
    return true;
}
void capture(int fd, Operation operation, std::int64_t result, std::int64_t bytes,
             int error, const sockaddr* address, socklen_t length,
             std::uint8_t flags, int batch_count = 0) noexcept {
    PreserveErrno preserve;
    auto& s = state();
    if (!s.enabled.load(std::memory_order_acquire) || observing) return;
    observing = true;
    Event event{};
    if (socket_metadata(fd, event, address, length)) {
        event.fd = fd;
        event.operation = operation;
        event.result = result;
        event.bytes = bytes;
        event.error = result < 0 ? error : 0;
        event.flags = flags | (probe_depth ? GuardProbe : 0);
        event.batch_count = batch_count;
        event.elapsed_ms = clock_ms();
        event.thread_id = static_cast<std::int64_t>(syscall(SYS_gettid));
        if (s.queue_mutex.try_lock()) {
            if (s.enabled.load(std::memory_order_relaxed)) {
                if (s.size < QUEUE_CAPACITY) {
                    s.events[(s.head + s.size) % QUEUE_CAPACITY] = event;
                    ++s.size;
                } else s.dropped.fetch_add(1, std::memory_order_relaxed);
            }
            s.queue_mutex.unlock();
        } else if (s.enabled.load(std::memory_order_relaxed))
            s.dropped.fetch_add(1, std::memory_order_relaxed);
    }
    observing = false;
}
using Write = ssize_t (*)(int, const void*, size_t);
using Writev = ssize_t (*)(int, const iovec*, int);
using Connect = int (*)(int, const sockaddr*, socklen_t);
using Send = ssize_t (*)(int, const void*, size_t, int);
using Sendto = ssize_t (*)(int, const void*, size_t, int, const sockaddr*, socklen_t);
using Sendmsg = ssize_t (*)(int, const msghdr*, int);
using Sendmmsg = int (*)(int, mmsghdr*, unsigned, int);
Write original_write = nullptr;
Writev original_writev = nullptr;
Connect original_connect = nullptr;
Send original_send = nullptr;
Sendto original_sendto = nullptr;
Sendmsg original_sendmsg = nullptr;
Sendmmsg original_sendmmsg = nullptr;
ssize_t observed_write(int fd, const void* data, size_t size) {
    Scope scope;
    auto result = original_write(fd, data, size);
    if (scope.outer()) observe(fd, Operation::Write, result, errno);
    return result;
}
ssize_t observed_writev(int fd, const iovec* data, int count) {
    Scope scope;
    auto result = original_writev(fd, data, count);
    if (scope.outer()) observe(fd, Operation::Writev, result, errno);
    return result;
}
int observed_connect(int fd, const sockaddr* address, socklen_t length) {
    Scope scope;
    auto result = original_connect(fd, address, length);
    if (scope.outer()) observe(fd, Operation::Connect, result, errno, address, length);
    return result;
}
ssize_t observed_send(int fd, const void* data, size_t size, int flags) {
    Scope scope;
    auto result = original_send(fd, data, size, flags);
    if (scope.outer()) observe(fd, Operation::Send, result, errno);
    return result;
}
ssize_t observed_sendto(int fd, const void* data, size_t size, int flags,
                        const sockaddr* address, socklen_t length) {
    Scope scope;
    auto result = original_sendto(fd, data, size, flags, address, length);
    if (scope.outer()) observe(fd, Operation::Sendto, result, errno, address, length);
    return result;
}
ssize_t observed_sendmsg(int fd, const msghdr* message, int flags) {
    Scope scope;
    auto result = original_sendmsg(fd, message, flags);
    if (scope.outer()) observe_message(fd, Operation::Sendmsg, result, errno, message);
    return result;
}
int observed_sendmmsg(int fd, mmsghdr* messages, unsigned count, int flags) {
    Scope scope;
    auto result = original_sendmmsg(fd, messages, count, flags);
    if (scope.outer()) observe_messages(fd, result, errno, messages, count);
    return result;
}
struct Hook {
    const char* name;
    void* replacement;
    void** original;
    void* target = nullptr;
    bool installed = false;
};
Hook hooks[]{
    {"write", reinterpret_cast<void*>(observed_write), reinterpret_cast<void**>(&original_write)},
    {"writev", reinterpret_cast<void*>(observed_writev), reinterpret_cast<void**>(&original_writev)},
    {"connect", reinterpret_cast<void*>(observed_connect), reinterpret_cast<void**>(&original_connect)},
    {"send", reinterpret_cast<void*>(observed_send), reinterpret_cast<void**>(&original_send)},
    {"sendto", reinterpret_cast<void*>(observed_sendto), reinterpret_cast<void**>(&original_sendto)},
    {"sendmsg", reinterpret_cast<void*>(observed_sendmsg), reinterpret_cast<void**>(&original_sendmsg)},
    {"sendmmsg", reinterpret_cast<void*>(observed_sendmmsg), reinterpret_cast<void**>(&original_sendmmsg)}
};
void* resolve(const char* name) {
#ifdef FKCLAUDE_HOST_TEST
    if (state().resolver) return state().resolver(name);
#endif
    return dlsym(RTLD_DEFAULT, name);
}
}
Scope::Scope() noexcept : outer_(call_depth++ == 0) {}
Scope::~Scope() { --call_depth; }
void probe(bool enter) noexcept {
    if (enter) ++probe_depth;
    else if (probe_depth) --probe_depth;
}
void set_hook_function(HookFunction hook) {
    std::lock_guard<std::mutex> lock(state().installation);
    state().hook = hook;
}
int install(bool ip_guard_enabled) {
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.installation);
    if (s.installed) return s.guard_mode == ip_guard_enabled ? s.mask : -1;
    if (!s.hook) return -1;
    s.guard_mode = ip_guard_enabled;
    // The caller has already installed the IP guard successfully when true.
    s.mask = ip_guard_enabled ? 0x7c : 0;
    unsigned limit = ip_guard_enabled ? 2 : 7;
    for (unsigned i = 0; i < limit; ++i) {
        auto& h = hooks[i];
        h.target = resolve(h.name);
        if (!h.target) continue;
        bool duplicate = false;
        for (unsigned j = 0; j < i; ++j)
            duplicate |= hooks[j].installed && hooks[j].target == h.target;
        if (duplicate) continue;
        if (s.hook(h.target, h.replacement, h.original) == 0 && *h.original) {
            h.installed = true;
            s.mask |= 1 << i;
        }
    }
    s.installed = true;
    return s.mask;
}
void set_enabled(bool value) noexcept {
    PreserveErrno preserve;
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.queue_mutex);
    s.enabled.store(value, std::memory_order_release);
    if (!value) {
        s.head = s.size = 0;
        s.events.fill(Event{});
        s.dropped.store(0, std::memory_order_relaxed);
    }
}
bool enabled() noexcept { return state().enabled.load(std::memory_order_acquire); }
void observe(int fd, Operation operation, std::int64_t result, int error,
             const sockaddr* address, socklen_t length, bool guard_blocked) noexcept {
    if (!enabled()) return;
    // Failed syscalls can receive invalid userspace pointers. Never inspect them.
    const bool accepted_address = result >= 0 ||
        (operation == Operation::Connect && error == EINPROGRESS);
    capture(fd, operation, result, operation == Operation::Connect ? 0 : (result > 0 ? result : 0),
            error, accepted_address ? address : nullptr, accepted_address ? length : 0,
            guard_blocked ? GuardBlocked : 0);
}
void observe_message(int fd, Operation operation, std::int64_t result, int error,
                     const msghdr* message, bool guard_blocked) noexcept {
    if (!enabled()) return;
    const auto* safe = result >= 0 ? message : nullptr;
    observe(fd, operation, result, error, safe ? static_cast<const sockaddr*>(safe->msg_name) : nullptr,
            safe ? safe->msg_namelen : 0, guard_blocked);
}
void observe_messages(int fd, int result, int error, const mmsghdr* messages,
                      unsigned count, bool guard_blocked) noexcept {
    PreserveErrno preserve;
    if (!enabled()) return;
    if (result <= 0 || !messages) {
        capture(fd, Operation::Sendmmsg, result, 0, error, nullptr, 0,
                guard_blocked ? GuardBlocked : 0, result > 0 ? result : 0);
        return;
    }
    unsigned accepted = static_cast<unsigned>(result);
    if (accepted > count) accepted = count;
    unsigned sampled = accepted > 16 ? 16 : accepted;
    for (unsigned i = 0; i < sampled; ++i) {
        const auto& message = messages[i];
        capture(fd, Operation::Sendmmsg, result, message.msg_len, 0,
                static_cast<const sockaddr*>(message.msg_hdr.msg_name), message.msg_hdr.msg_namelen,
                PerMessage, result);
    }
    if (accepted > sampled && enabled())
        state().dropped.fetch_add(accepted - sampled, std::memory_order_relaxed);
}
std::size_t drain(Event* events, std::size_t capacity, std::uint64_t& dropped) noexcept {
    PreserveErrno preserve;
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.queue_mutex);
    std::size_t count = events ? (s.size < capacity ? s.size : capacity) : 0;
    for (std::size_t i = 0; i < count; ++i) events[i] = s.events[(s.head + i) % QUEUE_CAPACITY];
    s.head = (s.head + count) % QUEUE_CAPACITY;
    s.size -= count;
    dropped = s.dropped.exchange(0, std::memory_order_relaxed);
    return count;
}
const char* operation_name(Operation operation) noexcept {
    switch (operation) {
        case Operation::Connect: return "connect";
        case Operation::Send: return "send";
        case Operation::Sendto: return "sendto";
        case Operation::Sendmsg: return "sendmsg";
        case Operation::Sendmmsg: return "sendmmsg";
        case Operation::Write: return "write";
        case Operation::Writev: return "writev";
    }
    return "unknown";
}
#ifdef FKCLAUDE_HOST_TEST
void set_resolver_for_test(void* (*resolver)(const char*)) { state().resolver = resolver; }
void reset_for_test() {
    set_enabled(false);
    auto& s = state();
    s.installed = false; s.mask = 0; s.guard_mode = false;
    for (auto& hook : hooks) { hook.installed = false; hook.target = nullptr; }
    call_depth = probe_depth = 0;
}
#endif
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_lightjunction_fkclaude_AuditNative_nativeInstall(JNIEnv*, jclass, jboolean guard) {
    try { return fkclaude::network_audit::install(guard == JNI_TRUE); }
    catch (...) { return -1; }
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_lightjunction_fkclaude_AuditNative_nativeSetEnabled(JNIEnv*, jclass, jboolean enabled) {
    fkclaude::network_audit::set_enabled(enabled == JNI_TRUE);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_lightjunction_fkclaude_AuditNative_nativeDrain(JNIEnv* env, jclass) {
    using namespace fkclaude::network_audit;
    std::array<Event, 128> events{};
    std::uint64_t dropped = 0;
    auto count = drain(events.data(), events.size(), dropped);
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    auto result = env->NewObjectArray(static_cast<jsize>(count + 1), string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (!result) return nullptr;
    char line[320];
    std::snprintf(line, sizeof(line), "dropped|%llu", static_cast<unsigned long long>(dropped));
    jstring dropped_line = env->NewStringUTF(line);
    if (!dropped_line) return nullptr;
    env->SetObjectArrayElement(result, 0, dropped_line);
    env->DeleteLocalRef(dropped_line);
    for (std::size_t i = 0; i < count && !env->ExceptionCheck(); ++i) {
        const auto& event = events[i];
        std::snprintf(line, sizeof(line), "%lld|%lld|%d|%s|%d|%s|%u|%d|%lld|%lld|%d|%u|%d",
                static_cast<long long>(event.elapsed_ms), static_cast<long long>(event.thread_id),
                event.fd, operation_name(event.operation), event.family, event.address, static_cast<unsigned>(event.port),
                event.socket_type, static_cast<long long>(event.result), static_cast<long long>(event.bytes),
                event.error, static_cast<unsigned>(event.flags), event.batch_count);
        jstring value = env->NewStringUTF(line);
        if (!value) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i + 1), value);
        env->DeleteLocalRef(value);
    }
    return env->ExceptionCheck() ? nullptr : result;
}
