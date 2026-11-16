#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "network_audit.hpp"
#include "ip_guard.hpp"
#include <arpa/inet.h>
#include <array>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <iostream>
#include <map>
#include <string>
#include <sys/socket.h>
#include <sys/uio.h>
#include <type_traits>
#include <unistd.h>
#include <vector>
namespace audit = fkclaude::network_audit;
namespace guard = fkclaude::ip_guard;
namespace {
std::map<std::string, void*> wrappers;
std::map<void*, std::string> names;
std::string missing;
std::uint64_t dropped = 0;
void* resolver(const char* name) {
    if (missing == name) return nullptr;
    auto target = dlsym(RTLD_DEFAULT, name);
    assert(target);
    names[target] = name;
    return target;
}
int hook(void* target, void* replacement, void** original) {
    assert(names.count(target));
    *original = target;
    wrappers[names.at(target)] = replacement;
    return 0;
}
template<class T> T wrap(const char* name) {
    assert(wrappers.count(name));
    return reinterpret_cast<T>(wrappers.at(name));
}
using C = int (*)(int, const sockaddr*, socklen_t);
using W = ssize_t (*)(int, const void*, size_t);
using WV = ssize_t (*)(int, const iovec*, int);
using S = ssize_t (*)(int, const void*, size_t, int);
using ST = ssize_t (*)(int, const void*, size_t, int, const sockaddr*, socklen_t);
using SM = ssize_t (*)(int, const msghdr*, int);
using SMM = int (*)(int, mmsghdr*, unsigned, int);
std::vector<audit::Event> take() {
    std::array<audit::Event, audit::QUEUE_CAPACITY> buffer{};
    auto count = audit::drain(buffer.data(), buffer.size(), dropped);
    return {buffer.begin(), buffer.begin() + count};
}
sockaddr_in loopback() {
    sockaddr_in result{};
    result.sin_family = AF_INET;
    result.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    return result;
}
struct Udp {
    int sender = socket(AF_INET, SOCK_DGRAM, 0);
    int receiver = socket(AF_INET, SOCK_DGRAM, 0);
    sockaddr_in destination = loopback();
    Udp() {
        assert(sender >= 0 && receiver >= 0);
        assert(bind(receiver, reinterpret_cast<sockaddr*>(&destination), sizeof(destination)) == 0);
        socklen_t size = sizeof(destination);
        assert(getsockname(receiver, reinterpret_cast<sockaddr*>(&destination), &size) == 0);
    }
    ~Udp() { close(sender); close(receiver); }
    ssize_t send(const void* data, size_t size) {
        return wrap<ST>("sendto")(sender, data, size, 0,
                reinterpret_cast<const sockaddr*>(&destination), sizeof(destination));
    }
};
struct Tcp {
    int client = -1, peer = -1;
    Tcp() {
        int listener = socket(AF_INET, SOCK_STREAM, 0);
        assert(listener >= 0);
        auto address = loopback();
        assert(bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0);
        assert(listen(listener, 1) == 0);
        socklen_t size = sizeof(address);
        assert(getsockname(listener, reinterpret_cast<sockaddr*>(&address), &size) == 0);
        client = socket(AF_INET, SOCK_STREAM, 0);
        assert(client >= 0);
        assert(wrap<C>("connect")(client, reinterpret_cast<sockaddr*>(&address), size) == 0);
        peer = accept(listener, nullptr, nullptr);
        assert(peer >= 0);
        close(listener);
    }
    ~Tcp() { close(client); close(peer); }
};
}
int main() {
    std::cout << "HOST FAKE INLINE HOOK: wrappers exercised, no libc patching\n";
    static_assert(std::is_trivially_copyable<audit::Event>::value);
    static_assert(sizeof(audit::Event) <= 128);
    audit::set_hook_function(hook);
    audit::set_resolver_for_test(resolver);
    missing = "writev";
    assert((audit::install(false) & 2) == 0);
    audit::reset_for_test();
    wrappers.clear(); missing.clear();
    assert(audit::install(false) == 127);
    assert(audit::install(false) == 127);
    assert(!audit::enabled());
    {
        Udp udp;
        assert(udp.send("off", 3) == 3);
        assert(take().empty());
        audit::set_enabled(true);
        errno = EDOM;
        assert(udp.send("private_payload", 15) == 15 && errno == EDOM);
        char received[40]{};
        assert(recv(udp.receiver, received, sizeof(received), 0) == 3);
        assert(recv(udp.receiver, received, sizeof(received), 0) == 15);
        assert(std::memcmp(received, "private_payload", 15) == 0);
        auto events = take();
        assert(events.size() == 1 && dropped == 0);
        const auto& e = events.front();
        assert(e.operation == audit::Operation::Sendto && e.result == 15 && e.bytes == 15);
        assert(e.family == 4 && e.port == ntohs(udp.destination.sin_port));
        assert(std::strcmp(e.address, "127.0.0.1") == 0 && e.error == 0);
        assert(e.thread_id > 0 && e.elapsed_ms > 0);
        // Only socket metadata exists in Event; body buffers are never passed to observe().
        assert(std::string(e.address).find("private_payload") == std::string::npos);

        int file = open("/dev/null", O_WRONLY);
        assert(file >= 0);
        errno = ECHILD;
        assert(wrap<W>("write")(file, "file", 4) == 4 && errno == ECHILD);
        close(file);
        int local[2]; assert(socketpair(AF_UNIX, SOCK_STREAM, 0, local) == 0);
        assert(wrap<W>("write")(local[0], "ipc", 3) == 3);
        close(local[0]); close(local[1]);
        assert(take().empty());
        // The observer must not dereference syscall arguments after EFAULT.
        // Call the observer directly here because ASan's libc interceptors may
        // inspect invalid arguments before the kernel can return EFAULT.
        errno = EFAULT;
        audit::observe(udp.sender, audit::Operation::Sendto, -1, errno,
                reinterpret_cast<const sockaddr*>(std::uintptr_t(1)), sizeof(sockaddr_in));
        assert(errno == EFAULT);
        events = take();
        assert(events.size() == 1 && events[0].error == EFAULT && events[0].bytes == 0);
        audit::observe_message(udp.sender, audit::Operation::Sendmsg, -1, EFAULT,
                reinterpret_cast<const msghdr*>(std::uintptr_t(1)));
        assert(errno == EFAULT && take().size() == 1);
        audit::observe_messages(udp.sender, -1, EFAULT,
                reinterpret_cast<const mmsghdr*>(std::uintptr_t(1)), 1);
        assert(errno == EFAULT && take().size() == 1);
        assert(wrap<W>("write")(-1, "x", 1) == -1 && errno == EBADF);
        assert(take().empty());

        iovec io{const_cast<char*>("z"), 1};
        std::array<mmsghdr, 18> batch{};
        for (auto& message : batch) {
            message.msg_hdr.msg_name = &udp.destination;
            message.msg_hdr.msg_namelen = sizeof(udp.destination);
            message.msg_hdr.msg_iov = &io;
            message.msg_hdr.msg_iovlen = 1;
        }
        assert(wrap<SMM>("sendmmsg")(udp.sender, batch.data(), batch.size(), 0) == 18);
        events = take();
        assert(events.size() == 16 && dropped == 2);
        for (const auto& event : events)
            assert(event.result == 18 && event.bytes == 1 && event.batch_count == 18
                    && (event.flags & audit::PerMessage));
        // Bounded queue and explicit loss counters under a sustained producer.
        for (unsigned i = 0; i < 600; ++i)
            audit::observe(udp.sender, audit::Operation::Send, 8, 0);
        events = take();
        assert(events.size() == audit::QUEUE_CAPACITY && dropped == 600 - audit::QUEUE_CAPACITY);
        audit::observe(udp.sender, audit::Operation::Send, 8, 0);
        audit::set_enabled(false);
        assert(take().empty() && dropped == 0);
        assert(udp.send("off", 3) == 3 && take().empty());
        audit::set_enabled(true);
        {
            audit::Scope outer;
            assert(outer.outer());
            audit::Scope inner;
            assert(!inner.outer());
            assert(udp.send("nested", 6) == 6);
        }
        assert(take().empty());
    }
    {
        Tcp tcp;
        auto events = take();
        assert(events.size() == 1 && events[0].operation == audit::Operation::Connect && events[0].bytes == 0);
        assert(wrap<S>("send")(tcp.client, "ab", 2, MSG_NOSIGNAL) == 2);
        assert(wrap<W>("write")(tcp.client, "cd", 2) == 2);
        iovec vectors[]{{const_cast<char*>("ef"), 2}, {const_cast<char*>("gh"), 2}};
        assert(wrap<WV>("writev")(tcp.client, vectors, 2) == 4);
        char received[8]{};
        size_t have = 0;
        while (have < sizeof(received)) {
            auto count = recv(tcp.peer, received + have, sizeof(received) - have, 0);
            assert(count > 0);
            have += static_cast<size_t>(count);
        }
        assert(std::memcmp(received, "abcdefgh", 8) == 0);
        events = take();
        assert(events.size() == 3);
        assert(events[0].bytes == 2 && events[1].bytes == 2 && events[2].bytes == 4);
        assert(events[1].operation == audit::Operation::Write);
        assert(events[2].operation == audit::Operation::Writev);
    }
    {
        int socket6 = socket(AF_INET6, SOCK_DGRAM, 0);
        assert(socket6 >= 0);
        sockaddr_in6 destination6{};
        destination6.sin6_family = AF_INET6;
        destination6.sin6_port = htons(9);
        destination6.sin6_addr = in6addr_loopback;
        assert(wrap<ST>("sendto")(socket6, "v6", 2, 0,
                reinterpret_cast<const sockaddr*>(&destination6), sizeof(destination6)) == 2);
        auto events = take();
        assert(events.size() == 1 && events[0].family == 6 && events[0].port == 9);
        assert(std::strcmp(events[0].address, "::1") == 0 && events[0].bytes == 2);
        close(socket6);
    }
    // The guard owns connect/send symbols; the observer only installs write/writev.
    audit::reset_for_test(); wrappers.clear();
    guard::set_hook_function(hook); guard::set_resolver_for_test(resolver);
    assert(guard::start());
    auto guarded_send = wrappers.at("send");
    assert(audit::install(true) == 127 && wrappers.at("send") == guarded_send);
    audit::set_enabled(true);
    {
        Udp udp;
        assert(udp.send("x", 1) == -1 && errno == EACCES);
        auto events = take();
        assert(events.size() == 1 && (events[0].flags & audit::GuardBlocked) && events[0].bytes == 0);
        guard::permit(4, 10000);
        assert(udp.send("x", 1) == 1);
        events = take(); assert(events.size() == 1 && events[0].bytes == 1);
    }
    guard::probe(true);
    {
        Udp probeUdp;
        assert(wrap<C>("connect")(probeUdp.sender, reinterpret_cast<const sockaddr*>(&probeUdp.destination),
                                 sizeof(probeUdp.destination)) == 0);
        take();
        guard::block();
        assert(probeUdp.send("p", 1) == 1);
        auto events = take();
        assert(events.size() == 1 && (events[0].flags & audit::GuardProbe));
        guard::probe(false);
    }
    audit::set_enabled(false);
    guard::stop_for_test();
    std::cout << "network audit tests passed: metadata, errno, payload preservation, pause, loss, shared guard\n";
}
