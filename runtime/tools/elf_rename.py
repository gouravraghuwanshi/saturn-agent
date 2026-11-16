#!/usr/bin/env python3
"""In-place rename of DT_NEEDED / DT_SONAME strings in 64-bit little-endian ELF files.

Only rewrites strings inside .dynstr, and only to names of equal or shorter
length (padded with NULs), so no section/segment layout changes are needed.

usage: elf_rename.py OUT_FILE IN_FILE old=new [old=new ...]
"""
import struct
import sys


def dynstr_range(data: bytes):
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("not a 64-bit little-endian ELF")
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    sh = lambda i: struct.unpack_from("<IIQQQQIIQQ", data, e_shoff + i * e_shentsize)
    shstr = sh(e_shstrndx)
    names_off = shstr[4]
    for i in range(e_shnum):
        s = sh(i)
        end = data.index(b"\0", names_off + s[0])
        if data[names_off + s[0]:end] == b".dynstr":
            return s[4], s[4] + s[5]
    raise SystemExit("no .dynstr section")


def main():
    out, src, *pairs = sys.argv[1:]
    data = bytearray(open(src, "rb").read())
    lo, hi = dynstr_range(data)
    for pair in pairs:
        old, new = (x.encode() for x in pair.split("=", 1))
        if len(new) > len(old):
            raise SystemExit(f"{pair}: new name longer than old")
        needle = b"\0" + old + b"\0"
        pos = data.find(needle, lo, hi)
        hits = 0
        while pos != -1:
            start = pos + 1
            data[start:start + len(old)] = new + b"\0" * (len(old) - len(new))
            hits += 1
            pos = data.find(needle, start, hi)
        if hits:
            print(f"  {pair}: {hits}x")
    open(out, "wb").write(data)


if __name__ == "__main__":
    main()
