#!/usr/bin/env python3
"""Builds CBACT02C input (carddata.txt) in the same ASCII layout as app/data/ASCII.

  cbact02c_data.py edge OUT_DIR [full|empty]   targeted edge cases (or an empty card file)
  cbact02c_data.py random SEED OUT_DIR         a random card file (same seed = same file)

Records are written in ascending card-number order, as a KSDS load requires.
"""
import random
import sys
from pathlib import Path

TEXT = "".join(chr(c) for c in range(32, 127))


def card(number, acct_id="00000000001", cvv="123", name="TEST HOLDER", expiry="2030-12-31", active="Y",
         filler=""):
    record = number + acct_id + cvv + name.ljust(50) + expiry + active + filler.ljust(59)
    if len(record) != 150:
        raise ValueError(f"card record is {len(record)} bytes")
    return record


def edge():
    """Each case is commented with what it checks; the program only lists records in key order."""
    return [
        card("0000000000000000", name="Lowest card number"),
        card("0000000000000001", "99999999999", "999", "Largest account ID and CVV"),
        card("4000000000000001", name="Inactive card", active="N"),
        card("4000000000000002", name="Short line, padded on load").rstrip(),
        card("4000000000000003", name="CRLF line ending") + "\r",
        card("4000000000000004", name="Filler used to the last byte", filler="X" * 58 + "Z"),
        card("4000000000000005", "0000000000{", "12}", "Overpunch signs in numeric fields"),
        card("4000000000000006", "ABCDEFGHIJK", "XYZ", "Letters in numeric fields", "not a date", " "),
        card("4000000000000007", name="", expiry=" " * 10, active=" "),          # blank name, date, status
        card("4000000000000008", name="!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ punctuation"),
        card("4000000000000009", "00000000000", "000", "Zero account ID and CVV", "1999-01-01"),
        card("4000000000000010", name="Longer than 150 bytes, cut on load") + "EXTRA BYTES",
        card("400000000000001A", name="Letter in card number"),
        card("9999999999999999", "99999999999", "999", "Highest card number, last record"),
    ]


def random_batch(seed):
    """0-60 cards with random keys (sometimes letters or spaces), random field contents and
    line lengths, sometimes CRLF; sorted by the padded 16-byte key, no duplicate keys."""
    rng = random.Random(seed)
    count = 0 if rng.random() < 0.05 else rng.randint(1, 60)
    records = {}
    while len(records) < count:
        roll = rng.random()
        if roll < 0.85:
            key = f"{rng.randrange(10 ** 16):016d}"
        else:
            key = "".join(rng.choice("0123456789 ABCXYZ") for _ in range(16))
        if key in records:
            continue
        record = key + "".join(rng.choice(TEXT) for _ in range(134))
        cut = rng.random()
        if cut < 0.2:
            record = record[:rng.randint(16, 150)]
        elif cut < 0.4:
            record = record[:rng.randint(16, 149)].ljust(150)
        records[key] = record + ("\r" if rng.random() < 0.1 else "")
    return [records[key] for key in sorted(records)]


def write(out_dir, cards):
    """One record per line; records ending in '\\r' are written with CRLF line endings."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "carddata.txt").write_bytes("".join(line + "\n" for line in cards).encode("ascii"))


def main(argv):
    if len(argv) in (3, 4) and argv[1] == "edge":
        variant = argv[3] if len(argv) == 4 else "full"
        if variant not in ("full", "empty"):
            sys.exit(__doc__)
        write(argv[2], edge() if variant == "full" else [])
    elif len(argv) == 4 and argv[1] == "random":
        write(argv[3], random_batch(int(argv[2])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
