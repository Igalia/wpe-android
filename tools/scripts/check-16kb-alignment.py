#!/usr/bin/env python3

"""
Fails when a native library in the given APKs is aligned to less than 16 KB pages.
Devices with 16 KB pages can't load those, and that includes the libraries under assets/,
which are loaded later with dlopen().
"""

import argparse
import struct
import sys
import zipfile

MIN_ALIGNMENT = 16 * 1024
PT_LOAD = 1


def load_alignment(header):
    # Only 64-bit little-endian ELF, the ABIs we ship.
    if header[:4] != b"\x7fELF" or header[4] != 2 or header[5] != 1:
        return None
    (phoff,) = struct.unpack_from("<Q", header, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", header, 0x36)
    alignments = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        (p_type,) = struct.unpack_from("<I", header, offset)
        if p_type == PT_LOAD:
            alignments.append(struct.unpack_from("<Q", header, offset + 0x30)[0])
    return min(alignments, default=None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", metavar="apk")
    args = parser.parse_args()

    misaligned = 0
    for apk in args.apks:
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                if not name.endswith(".so"):
                    continue
                with archive.open(name) as library:
                    alignment = load_alignment(library.read(64 * 1024))
                if alignment is not None and alignment < MIN_ALIGNMENT:
                    print(f"{apk}: {name} is aligned to {alignment} bytes")
                    misaligned += 1

    if misaligned:
        print(f"{misaligned} libraries are not aligned to 16 KB pages")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
