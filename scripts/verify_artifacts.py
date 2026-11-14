#!/usr/bin/env python3
"""Check exact hook contracts against the original DEX, not JADX's reconstructed Java."""
import json
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class Dex:
    def __init__(self, data):
        self.b = data
        if not data.startswith(b"dex\n"):
            raise ValueError("Unsupported DEX format")
        self.strings = []
        for i in range(self.u4(56)):
            _, off = self.uv(self.u4(self.u4(60) + 4 * i))
            self.strings.append(data[off:data.index(b"\0", off)].decode("utf-8", "replace"))
        self.types = [self.strings[self.u4(self.u4(68) + 4 * i)] for i in range(self.u4(64))]
        self.protos = []
        for i in range(self.u4(72)):
            _, result, off = struct.unpack_from("<III", data, self.u4(76) + 12 * i)
            args = [] if off == 0 else [self.types[struct.unpack_from("<H", data, off + 4 + 2 * j)[0]]
                                       for j in range(self.u4(off))]
            self.protos.append((tuple(args), self.types[result]))
        methods = []
        for i in range(self.u4(88)):
            owner, proto, name = struct.unpack_from("<HHI", data, self.u4(92) + 8 * i)
            methods.append((self.types[owner], self.strings[name], *self.protos[proto]))
        fields = []
        for i in range(self.u4(80)):
            owner, typ, name = struct.unpack_from("<HHI", data, self.u4(84) + 8 * i)
            fields.append((self.types[owner], self.strings[name], self.types[typ]))
        self.classes, self.fields, self.methods = set(), set(), set()
        for i in range(self.u4(96)):
            base = self.u4(100) + 32 * i
            self.classes.add(self.types[self.u4(base)])
            off = self.u4(base + 24)
            if not off:
                continue
            counts = []
            for _ in range(4):
                n, off = self.uv(off)
                counts.append(n)
            for n in counts[:2]:
                index = 0
                for _ in range(n):
                    diff, off = self.uv(off)
                    index += diff
                    _, off = self.uv(off)
                    self.fields.add(fields[index])
            for n in counts[2:]:
                index = 0
                for _ in range(n):
                    diff, off = self.uv(off)
                    index += diff
                    _, off = self.uv(off)
                    _, off = self.uv(off)
                    self.methods.add(methods[index])

    def u4(self, off):
        return struct.unpack_from("<I", self.b, off)[0]

    def uv(self, off):
        value = shift = 0
        while True:
            byte = self.b[off]
            off += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, off
            shift += 7


def read_apk(path):
    with zipfile.ZipFile(path) as apk:
        return [Dex(apk.read(n)) for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n)]


def main():
    sample = ROOT / "artifacts/claude-1.261112.18/base.apk"
    dexes = read_apk(sample)
    methods = set().union(*(d.methods for d in dexes))
    fields = set().union(*(d.fields for d in dexes))
    string = "Ljava/lang/String;"
    obj = "Ljava/lang/Object;"
    contracts = [
        ("Ls2h;", "<init>", (string,), "V"),
        ("Lpm3;", "r", ("I",) + (string,) * 5, "Ljava/util/Map;"),
        ("Ltdz;", "<init>", ("Lom20;",) + (string,) * 8 + ("Lyxo;",), "V"),
        ("Lv6g;", "invokeSuspend", (obj,), obj),
        ("Lh2a0;", "b", ("Leaj;", "Ly0u;", "Z"), "V"),
        ("Lg2a0;", "invokeSuspend", (obj,), obj),
        ("Lko10;", "f", (), "Lw040;"),
        ("Lo6n;", "b", (), string),
    ]
    for contract in contracts:
        assert contract in methods, ("Missing method contract", contract)
    field_contracts = [("Ls2h;", "a", string), ("Lv6g;", "r", obj),
                       ("Lko10;", "b", "Lbj30;"), ("Lbj30;", "a", "Lo6n;"),
                       ("Lo6n;", "d", string), ("Ldpf0;", "a", "Ldpf0;")]
    field_contracts += [("Let0;", name, string) for name in "abcdefghij"]
    field_contracts += [("Let0;", name, "Ljava/util/List;") for name in "klmno"]
    field_contracts += [("Ljo0;", name, typ) for name, typ in
                        [("a", string), ("b", string), ("c", "D"), ("d", "J"), ("e", "J"),
                         ("f", "J"), ("g", "Ljava/util/List;")]]
    for contract in field_contracts:
        assert contract in fields, ("Missing field contract", contract)
    schema = (ROOT / "app/src/main/java/io/github/lightjunction/fkclaude/Schema.java").read_text()
    keys = re.findall(r'new Field\(\d+, "([^"]+)"', schema)
    assert len(keys) == len(set(keys))
    for locale in ["values", "values-zh-rCN"]:
        tree = ET.parse(ROOT / "app/src/main/res" / locale / "strings.xml")
        names = {n.attrib["name"] for n in tree.getroot()}
        for key in keys:
            assert "field_" + key.replace(".", "_").lower() in names, (locale, key)
    module = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    with zipfile.ZipFile(module) as apk:
        assert apk.read("assets/xposed_init").decode().strip() == "io.github.lightjunction.fkclaude.EnvironmentHook"
        assert apk.read("assets/native_init").decode().strip() == "libfkclaude.so"
        assert apk.read("assets/environment.js") == (ROOT / "app/src/main/assets/environment.js").read_bytes()
        assert [n for n in apk.namelist() if n.endswith(".so")] == ["lib/arm64-v8a/libfkclaude.so"]
        native = apk.read("lib/arm64-v8a/libfkclaude.so")
        info = apk.getinfo("lib/arm64-v8a/libfkclaude.so")
    assert native[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", native, 18)[0] == 183
    program_offset = struct.unpack_from("<Q", native, 32)[0]
    program_size, program_count = struct.unpack_from("<HH", native, 54)
    alignments = [struct.unpack_from("<Q", native, program_offset + i * program_size + 48)[0]
                  for i in range(program_count) if struct.unpack_from("<I", native, program_offset + i * program_size)[0] == 1]
    assert alignments and all(a >= 16384 for a in alignments), "ELF load alignment below 16 KiB"
    section_offset = struct.unpack_from("<Q", native, 40)[0]
    section_size, section_count = struct.unpack_from("<HH", native, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", native, section_offset + i * section_size) for i in range(section_count)]
    exports = set()
    for section in sections:
        if section[1] != 11:
            continue
        strings = sections[section[6]]
        for offset in range(section[4], section[4] + section[5], section[9]):
            name, binding, visibility, defined, _, _ = struct.unpack_from("<IBBHQQ", native, offset)
            if defined and binding >> 4 in (1, 2) and visibility & 3 in (0, 3) and name:
                start = strings[4] + name
                exports.add(native[start:native.index(b"\0", start)].decode())
    assert exports == {"native_init", "Java_io_github_lightjunction_fkclaude_NativeBridge_configure"} | {
        "Java_io_github_lightjunction_fkclaude_IpGuard_" + name for name in ("nativeStart", "nativeBlock", "nativePermit", "nativeProbe")}, exports
    with module.open("rb") as binary:
        binary.seek(info.header_offset)
        local = binary.read(30)
        name_size, extra_size = struct.unpack_from("<HH", local, 26)
    assert info.compress_type == zipfile.ZIP_STORED
    assert (info.header_offset + 30 + name_size + extra_size) % 16384 == 0, "APK native library alignment below 16 KiB"
    module_classes = set().union(*(d.classes for d in read_apk(module)))
    assert "Lio/github/lightjunction/fkclaude/EnvironmentHook;" in module_classes
    assert not any(n.startswith("Lde/robv/android/xposed/") for n in module_classes), "Framework API bundled in APK"
    result = {"dex_method_contracts": len(contracts), "dex_field_contracts": len(field_contracts),
              "configurable_fields": len(keys), "locales_complete": ["en", "zh-CN"],
              "xposed_entry_packaged": True, "framework_api_not_bundled": True,
              "native_abi": "arm64-v8a", "native_exports": sorted(exports),
              "native_16k_elf_and_zip_alignment": True, "document_start_asset_packaged": True,
              "runtime_hooks_tested": False}
    out = ROOT / "artifacts/validation"
    out.mkdir(parents=True, exist_ok=True)
    version = json.loads((module.parent / "output-metadata.json").read_text())["elements"][0]["versionName"]
    (out / f"static-contracts-{version}.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
