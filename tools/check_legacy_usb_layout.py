#!/usr/bin/env python3
"""Check the shipped legacy USB library against API 16/17's 4 KiB ELF layout."""
import struct
import sys
import zipfile


def page_start(address, page):
    return address & -page


def page_end(address, page):
    return page_start(address + page - 1, page)


def check_elf(data, machine):
    if len(data) < 52 or data[:7] != b"\x7fELF\x01\x01\x01":
        raise ValueError("expected a little-endian ELF32 library")
    if struct.unpack_from("<HH", data, 16) != (3, machine):
        raise ValueError("unexpected ELF type or machine")
    headers = struct.unpack_from("<I", data, 28)[0]
    entry_size, count = struct.unpack_from("<HH", data, 42)
    if entry_size != 32 or not count or headers + count * entry_size > len(data):
        raise ValueError("invalid ELF program header table")
    loads, protected = [], []
    for i in range(count):
        kind, off, va, _, filesz, memsz, flags, align = struct.unpack_from(
            "<8I", data, headers + i * entry_size,
        )
        if kind == 1:  # PT_LOAD
            if filesz > memsz or off + filesz > len(data) or align < 4096 or (align & (align - 1)) or (off - va) % 4096:
                raise ValueError("invalid 4 KiB PT_LOAD layout")
            if flags & 2:  # PF_W
                loads.append((page_start(va, 4096), page_end(va + memsz, 4096)))
        elif kind == 0x6474E552:  # PT_GNU_RELRO
            protected.append((page_start(va, 4096), page_end(va + memsz, 4096)))
    if not protected:
        raise ValueError("missing GNU_RELRO")
    for start, end in protected:
        if not any(lo <= start < end <= hi for lo, hi in loads):
            raise ValueError(f"4 KiB GNU_RELRO [{start:#x}, {end:#x}) exceeds a mapped writable PT_LOAD")


def main(argv):
    if len(argv) != 2:
        print(f"Usage: {argv[0]} <apk>", file=sys.stderr)
        return 2
    try:
        with zipfile.ZipFile(argv[1]) as apk:
            for abi, machine in (("armeabi-v7a", 40), ("x86", 3)):
                try:
                    check_elf(apk.read(f"lib/{abi}/libopenhc_legacy_usb.so"), machine)
                except (KeyError, ValueError, struct.error) as error:
                    raise ValueError(f"{abi}: {error}") from error
                print(f"{abi}: API 16/17 4 KiB ELF layout OK")
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Legacy USB ELF check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
