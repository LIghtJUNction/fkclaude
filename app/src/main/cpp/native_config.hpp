#pragma once
#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace fkclaude {
struct Config {
    std::map<std::string, std::string, std::less<>> values;
    std::map<std::string, std::string, std::less<>> properties;
    std::map<std::string, std::string, std::less<>> environment;
    const std::string* find(std::string_view key) const;
    std::optional<std::uint64_t> number(std::string_view key) const;
    bool prefix(std::string_view prefix) const;
};
// Big endian FKC1, count, then (key byte length, key UTF-8, value byte length, value UTF-8).
Config decode(const std::uint8_t* data, std::size_t size);
std::size_t copy_text(char* destination, std::size_t capacity, std::string_view text);
struct Report { bool framework; int requested; int installed; int failed; bool accepted; };
Report activate(const std::uint8_t* data, std::size_t size);
#ifdef FKCLAUDE_HOST_TEST
void test_resolver(void* (*resolver)(const char*));
#endif
}
