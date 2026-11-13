#include "native_api.hpp"
#include "native_config.hpp"
#include <algorithm>
#include <atomic>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <thread>
#include <unistd.h>

struct prop_info { const char* name; const char* value; };
using Callback = void (*)(void*, const char*, const char*, std::uint32_t);
extern "C" LibraryLoaded native_init(const NativeAPIEntries*);
namespace {
std::map<std::string, void*> targets;
std::map<void*, void*> replacements;
int fake_hook(void* target, void* replacement, void** original) {
    *original = target; replacements[target] = replacement; return 0;
}
void* resolver(const char* name) { auto it = targets.find(name); return it == targets.end() ? nullptr : it->second; }
int original_get(const char*, char* output) { std::strcpy(output, "real"); return 4; }
int original_read(const prop_info* p, char* name, char* output) {
    if (name) std::strcpy(name, p->name); std::strcpy(output, p->value); return std::strlen(p->value);
}
void original_callback(const prop_info* p, Callback callback, void* cookie) { callback(cookie, p->name, p->value, 123); }
char* original_env(const char*) { return const_cast<char*>("real-env"); }
int original_uname(utsname* p) {
    if (!p) { errno = EFAULT; return -1; }
    std::memset(p, 0, sizeof *p); std::strcpy(p->sysname, "RealLinux"); std::strcpy(p->nodename, "real-host");
    std::strcpy(p->release, "real-release"); std::strcpy(p->version, "real-version"); std::strcpy(p->machine, "real-machine"); return 0;
}
long original_sysconf(int name) { return name == _SC_PAGESIZE ? 16384L : 513L; }
int original_sysinfo(struct sysinfo* p) {
    if (!p) { errno = EFAULT; return -1; }
    std::memset(p, 0, sizeof *p); p->mem_unit = 4096; p->uptime = 80; p->totalswap = 11; return 0;
}
int original_vfs(const char* path, struct statvfs* p) {
    if (!p || std::strcmp(path, "/fail") == 0) { errno = EACCES; return -1; }
    std::memset(p, 0, sizeof *p); p->f_bsize = 4096; p->f_frsize = 8192; p->f_files = 1234; return 0;
}
int original_fvfs(int, struct statvfs* p) { return original_vfs("/", p); }
int original_fs(const char* path, struct statfs* p) {
    if (!p || std::strcmp(path, "/fail") == 0) { errno = EACCES; return -1; }
    std::memset(p, 0, sizeof *p); p->f_bsize = 4096; p->f_files = 1234; return 0;
}
int original_ffs(int, struct statfs* p) { return original_fs("/", p); }
template <class F> F replacement(const char* name) {
    return reinterpret_cast<F>(replacements.at(targets.at(name)));
}
std::vector<std::uint8_t> encode(const std::map<std::string, std::string>& values) {
    std::vector<std::uint8_t> output;
    auto integer = [&](std::uint32_t n) { for (int i = 24; i >= 0; i -= 8) output.push_back((n >> i) & 255); };
    integer(0x464b4331); integer(values.size());
    for (auto& [key, value] : values) for (const auto* text : {&key, &value}) {
        integer(text->size()); output.insert(output.end(), text->begin(), text->end());
    }
    return output;
}
void rejected(const std::vector<std::uint8_t>& payload) {
    bool threw = false;
    try { (void) fkclaude::decode(payload.data(), payload.size()); }
    catch (const std::invalid_argument&) { threw = true; }
    assert(threw);
}
}

int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream fixture(argv[1]); std::string hex; fixture >> hex;
    assert(!hex.empty() && hex.size() % 2 == 0);
    std::vector<std::uint8_t> golden;
    for (std::size_t i = 0; i < hex.size(); i += 2) golden.push_back(std::stoul(hex.substr(i, 2), nullptr, 16));
    auto decoded = fkclaude::decode(golden.data(), golden.size());
    assert(*decoded.find("cpu_count") == "4"); assert(*decoded.find("prop.ro.product.model") == "隔离");
    assert(decoded.properties.at("ro.product.model") == "隔离");
    for (std::size_t i = 0; i < golden.size(); ++i) rejected({golden.begin(), golden.begin() + i});
    auto trailing = golden; trailing.push_back(0); rejected(trailing);
    auto unknown_version = golden; unknown_version[0] = 0; rejected(unknown_version);
    rejected(encode({{"cpu_count", "0"}})); rejected(encode({{"memory_total", "NaN"}}));
    rejected(encode({{"native_hostname", std::string(65, 'a')}}));
    rejected(encode({{"env.SECRET", std::string("a\0b", 3)}}));
    char small[5]{}; assert(fkclaude::copy_text(small, sizeof small, "隔离") == 3); assert(std::string(small) == "隔");
    assert(fkclaude::copy_text(nullptr, 0, "text") == 0);
    auto not_ready = fkclaude::activate(golden.data(), golden.size()); assert(!not_ready.framework && !not_ready.accepted);

    targets = {{"__system_property_get", reinterpret_cast<void*>(original_get)},
        {"__system_property_read", reinterpret_cast<void*>(original_read)},
        {"__system_property_read_callback", reinterpret_cast<void*>(original_callback)},
        {"getenv", reinterpret_cast<void*>(original_env)}, {"uname", reinterpret_cast<void*>(original_uname)},
        {"sysconf", reinterpret_cast<void*>(original_sysconf)}, {"sysinfo", reinterpret_cast<void*>(original_sysinfo)},
        {"statvfs", reinterpret_cast<void*>(original_vfs)}, {"fstatvfs", reinterpret_cast<void*>(original_fvfs)},
        {"statfs", reinterpret_cast<void*>(original_fs)}, {"fstatfs", reinterpret_cast<void*>(original_ffs)}};
    fkclaude::test_resolver(resolver);
    NativeAPIEntries api{1, fake_hook, nullptr}; assert(native_init(&api) != nullptr);
    std::map<std::string, std::string> values{{"prop.ro.product.model", "virtual-model"}, {"prop.empty", ""},
        {"env.TZ", "UTC"}, {"env.SECRET", ""}, {"cpu_count", "4"}, {"memory_total", "65536"},
        {"memory_available", "32768"}, {"storage_total", "262144"}, {"storage_available", "131072"},
        {"native_hostname", "virtual-host"}, {"native_kernel_release", "virtual-release"}};
    std::string long_value;
    for (int i = 0; i < 40; ++i) long_value += "隔";
    values["prop.long"] = long_value;
    auto payload = encode(values);
    auto report = fkclaude::activate(payload.data(), payload.size());
    assert(report.framework && report.accepted && report.requested == 11 && report.installed == 11 && report.failed == 0);
    assert(fkclaude::activate(payload.data(), payload.size()).accepted);
    auto changed = encode({{"env.TZ", "Asia/Taipei"}}); assert(!fkclaude::activate(changed.data(), changed.size()).accepted);
    auto get = replacement<int (*)(const char*, char*)>("__system_property_get");
    char output[94]; std::memset(output, 'X', sizeof output);
    assert(get("ro.product.model", output) == 13 && std::string(output) == "virtual-model");
    assert(output[92] == 'X' && output[93] == 'X');
    assert(get("unconfigured", output) == 4 && std::string(output) == "real");
    assert(get("empty", output) == 0 && output[0] == 0);
    assert(get("long", output) == 90 && std::strlen(output) == 90);
    auto read = replacement<int (*)(const prop_info*, char*, char*)>("__system_property_read");
    prop_info pi{"ro.product.model", "real"}; char name[128];
    assert(read(&pi, name, output) == 13 && std::string(name) == pi.name && std::string(output) == "virtual-model");
    assert(read(&pi, nullptr, output) == 13 && std::string(output) == "virtual-model");
    auto callback = replacement<void (*)(const prop_info*, Callback, void*)>("__system_property_read_callback");
    prop_info large{"long", "real"}; std::string callback_value;
    callback(&large, [](void* p, const char*, const char* value, std::uint32_t serial) {
        assert(serial == 123); *static_cast<std::string*>(p) = value;
    }, &callback_value);
    assert(callback_value == long_value);
    auto env = replacement<char* (*)(const char*)>("getenv");
    errno = EBUSY; char* retained = env("TZ"); assert(std::string(retained) == "UTC" && errno == EBUSY);
    assert(env("SECRET") == nullptr); assert(std::string(env("UNKNOWN")) == "real-env");
    assert(std::string(retained) == "UTC");
    auto uname = replacement<int (*)(utsname*)>("uname"); utsname machine{};
    assert(uname(&machine) == 0 && std::string(machine.nodename) == "virtual-host");
    assert(std::string(machine.release) == "virtual-release" && std::string(machine.machine) == "real-machine");
    assert(uname(nullptr) == -1 && errno == EFAULT);
    auto sysconf = replacement<long (*)(int)>("sysconf");
    assert(sysconf(_SC_NPROCESSORS_ONLN) == 4 && sysconf(_SC_NPROCESSORS_CONF) == 4);
    assert(sysconf(_SC_PHYS_PAGES) == 4 && sysconf(_SC_AVPHYS_PAGES) == 2 && sysconf(_SC_PAGESIZE) == 16384);
    assert(sysconf(_SC_OPEN_MAX) == 513);
    auto sysinfo = replacement<int (*)(struct sysinfo*)>("sysinfo"); struct sysinfo memory{};
    assert(sysinfo(&memory) == 0 && memory.totalram * memory.mem_unit == 65536 && memory.freeram * memory.mem_unit == 32768);
    assert(memory.uptime == 80 && memory.totalswap == 11);
    auto statvfs = replacement<int (*)(const char*, struct statvfs*)>("statvfs"); struct statvfs disk{};
    assert(statvfs("/", &disk) == 0 && disk.f_blocks == 32 && disk.f_bavail == 16 && disk.f_files == 1234);
    assert(statvfs("/fail", &disk) == -1 && errno == EACCES);
    auto fstatvfs = replacement<int (*)(int, struct statvfs*)>("fstatvfs"); assert(fstatvfs(3, &disk) == 0 && disk.f_blocks == 32);
    auto statfs = replacement<int (*)(const char*, struct statfs*)>("statfs"); struct statfs fs{};
    assert(statfs("/", &fs) == 0 && fs.f_blocks == 64 && fs.f_bavail == 32);
    auto fstatfs = replacement<int (*)(int, struct statfs*)>("fstatfs"); assert(fstatfs(3, &fs) == 0 && fs.f_blocks == 64);
    std::vector<std::thread> threads;
    for (int n = 0; n < 8; ++n) threads.emplace_back([&] {
        for (int i = 0; i < 5000; ++i) { char b[92]; assert(get("ro.product.model", b) == 13); assert(env("TZ") == retained); }
    });
    for (auto& thread : threads) thread.join();
    std::cout << "Native codec and 11 hook adapters passed; 40,000 concurrent reads passed. Framework inline patching is not simulated.\n";
}
