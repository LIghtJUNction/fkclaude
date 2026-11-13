#pragma once
#include <cstdint>

// ABI documented at https://github.com/LSPosed/LSPosed/wiki/Native-Hook
using HookFunction = int (*)(void*, void*, void**);
using UnhookFunction = int (*)(void*);
using LibraryLoaded = void (*)(const char*, void*);
struct NativeAPIEntries {
    std::uint32_t version;
    HookFunction hook_func;
    UnhookFunction unhook_func;
};
