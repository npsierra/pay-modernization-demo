#!/usr/bin/env python3
"""Prints CBACT01C's binary outputs (OUTFILE.dat, ARRYFILE.dat, VBRCFILE.dat) one record per
line, with COMP-3 fields shown as value[hex]. Usage: cbact01c_view.py DIR
"""
import sys
from pathlib import Path


def packed(raw):
    """COMP-3 PIC S9(10)V99 (7 bytes): value[hex], or ?[hex] if the bytes are not valid packed."""
    nibbles = raw.hex()
    digits, sign = nibbles[:-1], nibbles[-1]
    if not digits.isdigit() or sign not in "abcdef":
        return f"?[{nibbles}]"
    value = f"{int(digits[:-2])}.{digits[-2:]}"
    return f"{'-' if sign in 'bd' else '+'}{value}[{nibbles}]"


def text(raw):
    return raw.decode("latin-1")


def outfile(data):
    for i in range(0, len(data), 107):
        r = data[i:i + 107]
        yield " ".join([text(r[0:11]), text(r[11:12]), text(r[12:24]), text(r[24:36]), text(r[36:48]),
                        text(r[48:58]), text(r[58:68]), repr(text(r[68:78])), text(r[78:90]),
                        packed(r[90:97]), repr(text(r[97:107]))])


def arryfile(data):
    for i in range(0, len(data), 110):
        r = data[i:i + 110]
        slots = [text(r[11 + k * 19:23 + k * 19]) + "/" + packed(r[23 + k * 19:30 + k * 19]) for k in range(5)]
        yield " ".join([text(r[0:11])] + slots + [repr(text(r[106:110]))])


def vbrcfile(data):
    pos = 0
    while pos + 4 <= len(data):
        length = data[pos] * 256 + data[pos + 1]
        yield f"len={length} prefix={data[pos:pos + 4].hex()} {text(data[pos + 4:pos + 4 + length])}"
        pos += 4 + length
    if pos != len(data):
        yield f"?? {len(data) - pos} trailing bytes"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    folder = Path(sys.argv[1])
    for name, view in (("OUTFILE.dat", outfile), ("ARRYFILE.dat", arryfile), ("VBRCFILE.dat", vbrcfile)):
        path = folder / name
        print(f"== {name}" + ("" if path.exists() else " (missing)"))
        if path.exists():
            for line in view(path.read_bytes()):
                print(line)


if __name__ == "__main__":
    main()
