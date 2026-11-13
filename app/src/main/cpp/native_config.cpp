#include "native_config.hpp"
#include <algorithm>
#include <charconv>
#include <cstring>
#include <stdexcept>

namespace fkclaude {
const std::string* Config::find(std::string_view key) const {
    auto it = values.find(key);
    return it == values.end() ? nullptr : &it->second;
}
std::optional<std::uint64_t> Config::number(std::string_view key) const {
    const auto* text = find(key);
    if (!text || text->empty()) return std::nullopt;
    std::uint64_t result{};
    auto parsed = std::from_chars(text->data(), text->data() + text->size(), result);
    if (parsed.ec != std::errc{} || parsed.ptr != text->data() + text->size()) return std::nullopt;
    return result;
}
bool Config::prefix(std::string_view prefix) const {
    auto it = values.lower_bound(prefix);
    return it != values.end() && it->first.compare(0, prefix.size(), prefix) == 0;
}
Config decode(const std::uint8_t* data, std::size_t size) {
    if (!data || size < 8 || size > 1024 * 1024) throw std::invalid_argument("payload size");
    std::size_t offset = 0;
    auto integer = [&]() {
        if (size - offset < 4) throw std::invalid_argument("truncated integer");
        std::uint32_t value = 0;
        for (int i = 0; i < 4; ++i) value = (value << 8) | data[offset++];
        return value;
    };
    if (integer() != 0x464b4331) throw std::invalid_argument("payload version");
    auto count = integer();
    if (count > 512) throw std::invalid_argument("field count");
    auto string = [&](std::size_t limit) {
        auto length = integer();
        if (length > limit || length > size - offset) throw std::invalid_argument("string size");
        std::string value(reinterpret_cast<const char*>(data + offset), length);
        offset += length;
        if (value.find('\0') != std::string::npos) throw std::invalid_argument("embedded NUL");
        return value;
    };
    Config config;
    for (std::uint32_t i = 0; i < count; ++i) {
        auto key = string(256);
        auto value = string(16384);
        if (key.empty() || !config.values.emplace(std::move(key), std::move(value)).second)
            throw std::invalid_argument("duplicate or empty key");
    }
    if (offset != size) throw std::invalid_argument("trailing bytes");
    for (const auto& [key, value] : config.values) {
        if (key.compare(0, 5, "prop.") == 0) config.properties.emplace(key.substr(5), value);
        if (key.compare(0, 4, "env.") == 0) config.environment.emplace(key.substr(4), value);
    }
    for (auto [key, max] : std::vector<std::pair<const char*, std::uint64_t>>{
            {"cpu_count", 256}, {"memory_total", INT64_MAX}, {"memory_available", INT64_MAX},
            {"storage_total", INT64_MAX}, {"storage_available", INT64_MAX}}) {
        if (config.find(key) && (!config.number(key) || *config.number(key) > max))
            throw std::invalid_argument("numeric value");
    }
    if (config.number("cpu_count") == 0) throw std::invalid_argument("cpu count");
    for (const char* field : {"native_sysname", "native_hostname", "native_kernel_release", "native_kernel_version", "native_machine"})
        if (auto* v = config.find(field); v && v->size() > 64) throw std::invalid_argument("uname field size");
    return config;
}
std::size_t copy_text(char* destination, std::size_t capacity, std::string_view text) {
    if (!destination || capacity == 0) return 0;
    auto length = std::min(capacity - 1, text.size());
    // Never return a partial UTF-8 code point when a legacy buffer is smaller than the value.
    if (length < text.size()) while (length > 0 && (static_cast<unsigned char>(text[length]) & 0xc0) == 0x80) --length;
    std::memcpy(destination, text.data(), length);
    destination[length] = '\0';
    return length;
}
}
