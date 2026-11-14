#pragma once
#include "native_api.hpp"

namespace fkclaude::ip_guard {
using HookFunction = ::HookFunction;
void set_hook_function(HookFunction hook);
bool start();
void block();
void permit(int family, int ttl_ms);
std::int64_t permit_checked(int family, int ttl_ms, std::int64_t observation_deadline);
void probe(bool enter);
#ifdef FKCLAUDE_HOST_TEST
// Fake hooks in host tests capture wrappers; they do not patch libc.
void set_resolver_for_test(void* (*resolver)(const char*));
void stop_for_test();
#endif
}
