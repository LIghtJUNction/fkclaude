#pragma once
#include "native_api.hpp"
#include <cstddef>
#include <cstdint>
#include <sys/socket.h>
struct mmsghdr;

namespace fkclaude::network_audit {
constexpr std::size_t QUEUE_CAPACITY = 512;
enum class Operation : std::uint8_t { Connect, Send, Sendto, Sendmsg, Sendmmsg, Write, Writev };
enum Flag : std::uint8_t { GuardBlocked = 1, PerMessage = 2, GuardProbe = 4 };
struct Event {
    std::int64_t elapsed_ms = 0;
    std::int64_t thread_id = 0;
    std::int64_t result = 0;
    std::int64_t bytes = 0;
    int fd = -1;
    int error = 0;
    int family = 0;
    int socket_type = 0;
    int batch_count = 0;
    std::uint16_t port = 0;
    Operation operation = Operation::Connect;
    std::uint8_t flags = 0;
    char address[46]{};
};
// Shares nesting state with IP-guard wrappers, preventing double counting when
// libc implements one observed API by calling another observed API.
class Scope {
    bool outer_;
public:
    Scope() noexcept;
    ~Scope();
    bool outer() const noexcept { return outer_; }
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;
};
void probe(bool enter) noexcept;
void set_hook_function(HookFunction hook);
int install(bool ip_guard_enabled);
void set_enabled(bool enabled) noexcept;
bool enabled() noexcept;
void observe(int fd, Operation operation, std::int64_t result, int error,
             const sockaddr* address = nullptr, socklen_t length = 0,
             bool guard_blocked = false) noexcept;
void observe_message(int fd, Operation operation, std::int64_t result, int error,
                     const msghdr* message, bool guard_blocked = false) noexcept;
void observe_messages(int fd, int result, int error, const mmsghdr* messages,
                      unsigned count, bool guard_blocked = false) noexcept;
std::size_t drain(Event* events, std::size_t capacity, std::uint64_t& dropped) noexcept;
const char* operation_name(Operation operation) noexcept;
#ifdef FKCLAUDE_HOST_TEST
void set_resolver_for_test(void* (*resolver)(const char*));
void set_before_enqueue_for_test(void (*callback)());
void reset_for_test();
#endif
}
