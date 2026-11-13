#include "native_api.hpp"
#include "native_config.hpp"
#include <atomic>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <memory>
#include <mutex>
#include <set>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#else
struct prop_info;
#define PROP_VALUE_MAX 92
#endif

namespace {
using fkclaude::Config;
std::shared_ptr<const Config> config;
HookFunction hook_function = nullptr;
std::mutex install_mutex;
fkclaude::Report report{};
std::set<void*> installed_targets;
int (*original_property_get)(const char*, char*) = nullptr;
int (*original_property_read)(const prop_info*, char*, char*) = nullptr;
using ReadCallback = void (*)(void*, const char*, const char*, std::uint32_t);
void (*original_property_callback)(const prop_info*, ReadCallback, void*) = nullptr;
char* (*original_getenv)(const char*) = nullptr;
int (*original_uname)(utsname*) = nullptr;
long (*original_sysconf)(int) = nullptr;
int (*original_sysinfo)(struct sysinfo*) = nullptr;
int (*original_statvfs)(const char*, struct statvfs*) = nullptr;
int (*original_fstatvfs)(int, struct statvfs*) = nullptr;
int (*original_statfs)(const char*, struct statfs*) = nullptr;
int (*original_fstatfs)(int, struct statfs*) = nullptr;
#ifdef FKCLAUDE_HOST_TEST
void* (*resolve_for_test)(const char*) = nullptr;
#endif
std::shared_ptr<const Config> snapshot() { return std::atomic_load_explicit(&config, std::memory_order_acquire); }
const std::string* property(const Config& view, const char* name) {
    if (!name) return nullptr;
    auto it = view.properties.find(name);
    return it == view.properties.end() ? nullptr : &it->second;
}
int property_get(const char* name, char* value) {
    if (auto view = snapshot(); view && value) if (auto* v = property(*view, name))
        return static_cast<int>(fkclaude::copy_text(value, PROP_VALUE_MAX, *v));
    return original_property_get(name, value);
}
struct ReadContext { std::shared_ptr<const Config> view; ReadCallback callback; void* cookie; };
void read_result(void* cookie, const char* name, const char* value, std::uint32_t serial) {
    auto& read = *static_cast<ReadContext*>(cookie);
    auto* replacement = property(*read.view, name);
    read.callback(read.cookie, name, replacement ? replacement->c_str() : value, serial);
}
void property_callback(const prop_info* info, ReadCallback callback, void* cookie) {
    auto view = snapshot();
    if (!view || !callback) { original_property_callback(info, callback, cookie); return; }
    ReadContext read{std::move(view), callback, cookie};
    original_property_callback(info, read_result, &read);
}
int property_read(const prop_info* info, char* name, char* value) {
    int result = original_property_read(info, name, value);
    auto view = snapshot();
    if (!view || !value) return result;
    if (name) {
        if (auto* replacement = property(*view, name))
            return static_cast<int>(fkclaude::copy_text(value, PROP_VALUE_MAX, *replacement));
    } else if (original_property_callback) {
        struct Probe { const Config* view; char* value; int length; } probe{view.get(), value, result};
        original_property_callback(info, [](void* p, const char* n, const char*, std::uint32_t) {
            auto& probe = *static_cast<Probe*>(p);
            if (auto* v = property(*probe.view, n)) probe.length = static_cast<int>(fkclaude::copy_text(probe.value, PROP_VALUE_MAX, *v));
        }, &probe);
        return probe.length;
    }
    return result;
}
char* get_environment(const char* name) {
    if (auto view = snapshot(); view && name) {
        auto it = view->environment.find(name);
        if (it != view->environment.end()) return it->second.empty() ? nullptr : const_cast<char*>(it->second.c_str());
    }
    return original_getenv(name);
}
int system_name(utsname* result) {
    int status = original_uname(result);
    auto view = snapshot();
    if (status != 0 || !result || !view) return status;
    auto set = [&](auto& field, const char* key) {
        if (auto* v = view->find(key)) fkclaude::copy_text(field, sizeof field, *v);
    };
    set(result->sysname, "native_sysname"); set(result->nodename, "native_hostname");
    set(result->release, "native_kernel_release"); set(result->version, "native_kernel_version");
    set(result->machine, "native_machine");
    return status;
}
long system_config(int name) {
    auto view = snapshot();
    if (view) {
        if (name == _SC_NPROCESSORS_ONLN || name == _SC_NPROCESSORS_CONF)
            if (auto value = view->number("cpu_count")) return static_cast<long>(*value);
        const char* key = name == _SC_PHYS_PAGES ? "memory_total" : name == _SC_AVPHYS_PAGES ? "memory_available" : nullptr;
        if (key) if (auto value = view->number(key)) {
            long page = original_sysconf(_SC_PAGESIZE);
            if (page > 0) return static_cast<long>(*value / static_cast<std::uint64_t>(page));
        }
    }
    return original_sysconf(name);
}
int system_memory(struct sysinfo* result) {
    int status = original_sysinfo(result);
    auto view = snapshot();
    if (status || !result || !view) return status;
    auto unit = result->mem_unit;
    if (!unit) return status;
    if (auto value = view->number("memory_total")) result->totalram = *value / unit;
    if (auto value = view->number("memory_available")) result->freeram = *value / unit;
    return status;
}
template <class T> void storage(T* result, unsigned long block) {
    auto view = snapshot();
    if (!view || !result || !block) return;
    if (auto value = view->number("storage_total")) result->f_blocks = *value / block;
    if (auto value = view->number("storage_available")) result->f_bfree = result->f_bavail = *value / block;
}
int storage_vfs(const char* path, struct statvfs* result) {
    int status = original_statvfs(path, result);
    if (!status && result) storage(result, result->f_frsize ? result->f_frsize : result->f_bsize);
    return status;
}
int descriptor_vfs(int fd, struct statvfs* result) {
    int status = original_fstatvfs(fd, result);
    if (!status && result) storage(result, result->f_frsize ? result->f_frsize : result->f_bsize);
    return status;
}
int storage_fs(const char* path, struct statfs* result) {
    int status = original_statfs(path, result);
    if (!status && result) storage(result, result->f_bsize);
    return status;
}
int descriptor_fs(int fd, struct statfs* result) {
    int status = original_fstatfs(fd, result);
    if (!status && result) storage(result, result->f_bsize);
    return status;
}
void* resolve(const char* name) {
#ifdef FKCLAUDE_HOST_TEST
    if (resolve_for_test) return resolve_for_test(name);
#endif
    return dlsym(RTLD_DEFAULT, name);
}
template <class F> void install(const char* name, F replacement, F* original) {
    ++report.requested;
    void* target = resolve(name);
    if (!target || installed_targets.count(target)) { ++report.failed; return; }
    if (hook_function(target, reinterpret_cast<void*>(replacement), reinterpret_cast<void**>(original)) == 0 && *original) {
        installed_targets.insert(target); ++report.installed;
    } else ++report.failed;
}
void library_loaded(const char*, void*) {} // libc is already loaded; inline hooks also cover future consumers.
}

namespace fkclaude {
#ifdef FKCLAUDE_HOST_TEST
void test_resolver(void* (*resolver)(const char*)) { resolve_for_test = resolver; }
#endif
Report activate(const std::uint8_t* data, std::size_t size) {
    // Decode completely before publication. Once published, getenv pointers stay valid for process lifetime.
    auto candidate = std::make_shared<const Config>(decode(data, size));
    std::lock_guard<std::mutex> lock(install_mutex);
    if (auto existing = snapshot()) {
        auto current = report; current.accepted = existing->values == candidate->values; return current;
    }
    report.framework = hook_function != nullptr;
    if (!report.framework) return report;
    std::atomic_store_explicit(&config, std::move(candidate), std::memory_order_release);
    auto view = snapshot();
    report.accepted = true;
    if (view->prefix("prop.")) {
        install("__system_property_get", property_get, &original_property_get);
        install("__system_property_read", property_read, &original_property_read);
        install("__system_property_read_callback", property_callback, &original_property_callback);
    }
    if (view->prefix("env.")) install("getenv", get_environment, &original_getenv);
    bool uname_needed = false;
    for (const char* key : {"native_sysname", "native_hostname", "native_kernel_release", "native_kernel_version", "native_machine"})
        uname_needed |= view->find(key) != nullptr;
    if (uname_needed) install("uname", system_name, &original_uname);
    if (view->find("cpu_count") || view->find("memory_total") || view->find("memory_available"))
        install("sysconf", system_config, &original_sysconf);
    if (view->find("memory_total") || view->find("memory_available")) install("sysinfo", system_memory, &original_sysinfo);
    if (view->find("storage_total") || view->find("storage_available")) {
        install("statvfs", storage_vfs, &original_statvfs); install("fstatvfs", descriptor_vfs, &original_fstatvfs);
        install("statfs", storage_fs, &original_statfs); install("fstatfs", descriptor_fs, &original_fstatfs);
    }
    return report;
}
}

extern "C" __attribute__((visibility("default"), used))
LibraryLoaded native_init(const NativeAPIEntries* entries) {
    if (entries) hook_function = entries->hook_func;
    return library_loaded;
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_lightjunction_fkclaude_NativeBridge_configure(JNIEnv* env, jclass, jbyteArray encoded) {
    if (!encoded) return nullptr;
    auto size = env->GetArrayLength(encoded);
    if (size < 8 || size > 1024 * 1024) return nullptr;
    try {
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(encoded, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        auto result = fkclaude::activate(bytes.data(), bytes.size());
        jlong values[]{result.framework, result.requested, result.installed, result.failed, result.accepted};
        auto array = env->NewLongArray(5);
        if (array) env->SetLongArrayRegion(array, 0, 5, values);
        return array;
    } catch (const std::exception&) { return nullptr; }
}
