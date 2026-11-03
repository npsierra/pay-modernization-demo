#!/usr/bin/env python3
"""Builds CBACT02C input (carddata.txt) in the same ASCII layout as app/data/ASCII.

  cbact02c_data.py edge OUT_DIR [full|empty|no-carddata]
                                       targeted edge cases; the variants give an empty card
                                       file or leave it out (abend)
  cbact02c_data.py random SEED OUT_DIR a random card file (same seed = same file)
"""
import random
import sys
from pathlib import Path

TEXT = "".join(chr(c) for c in range(32, 127))
RECORD = 150
TEXT_RECORD = 500  # KSDSUTIL's line-sequential record; GnuCOBOL 3.2 splits longer lines


def card(number, acct_id="00000000001", cvv="123", name="TEST HOLDER", expiry="2030-12-31", status="Y",
         filler=""):
    record = number + acct_id + cvv + name.ljust(50) + expiry + status + filler.ljust(59)
    assert len(record) == RECORD, len(record)
    return record


def loaded_keys(line):
    """Keys of the records KSDSUTIL LOAD CARD makes from one text line (CRs are dropped first)."""
    line = line.replace("\r", "")
    pieces = [line[i:i + TEXT_RECORD] for i in range(0, max(len(line), 1), TEXT_RECORD)]
    return [piece[:RECORD].ljust(RECORD)[:16] for piece in pieces]


def edge():
    """Each case is commented with what the listing shows. Keys ascend, as the KSDS load needs."""
    split_line = "0000000000000009" + "SPLIT FIRST PART".ljust(484, "-") + "0000000000000010SPLIT REMAINDER"
    assert len(split_line) > TEXT_RECORD
    return [
        "",                                                          # empty line: a card of 150 spaces
        card("0000000000000001", name="ACTIVE CARD"),
        card("0000000000000002", name="INACTIVE CARD", status="N"),   # listed: the status is not checked
        card("0000000000000003", name="BLANK STATUS", status=" "),
        card("0000000000000004", name="SHORT LINE").rstrip(" "),        # padded to 150 on load
        card("0000000000000005", name="CRLF LINE") + "\r",              # line end dropped
        card("0000000000000006", acct_id="ABCDEFGHIJK", cvv="X9}", name="O'Neil-Smith, J. #2 & Co.",
             expiry="31/12/2030", status="X", filler="FILLER IS SHOWN TOO"),  # no field is validated
        card("0000000000000007", name="LONG LINE") + "CUT AFTER COLUMN 150" * 3,  # cut to 150
        "0000000000000008 TINY",                                      # 21 bytes
        split_line,                                                   # over 500 bytes: two cards
        card("9999999999999999", acct_id="99999999999", cvv="999", name="HIGHEST KEY", filler="9" * 59),
    ]


def random_batch(seed):
    """A random card file: digit and text keys, active/inactive/odd statuses, random text in
    every field, short, long, trimmed and CRLF lines, sometimes a blank card or no cards."""
    rng = random.Random(seed)

    def text(width):
        return "".join(rng.choice(TEXT) for _ in range(width))

    count = 0 if rng.random() < 0.05 else rng.randint(1, 40)
    lines = {}
    if rng.random() < 0.1:
        lines[" " * 16] = ""
    for _ in range(count):
        number = f"{rng.randrange(10 ** 16):016d}" if rng.random() < 0.9 else text(16)
        acct = f"{rng.randrange(10 ** 11):011d}" if rng.random() < 0.9 else text(11)
        cvv = f"{rng.randrange(1000):03d}" if rng.random() < 0.9 else text(3)
        expiry = (f"{rng.randint(2020, 2035)}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}"
                  if rng.random() < 0.8 else text(10))
        filler = " " * 59 if rng.random() < 0.7 else text(59)
        line = card(number, acct, cvv, text(rng.randint(0, 50)), expiry, rng.choice("YYYYNN X"), filler)
        roll = rng.random()
        if roll < 0.1:
            line = line.rstrip(" ")
        elif roll < 0.2:
            line += "\r"
        elif roll < 0.3:
            line = line[:rng.randint(1, RECORD - 1)]
        elif roll < 0.4:
            line += text(rng.randint(1, 150))
        keys = loaded_keys(line)
        if keys[0] not in lines:
            lines[keys[0]] = line
    return [lines[key] for key in sorted(lines)]


def write(out_dir, cards, variant="full"):
    """carddata.txt for CBACT02C; lines ending in '\\r' are written with CRLF line endings."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    if variant == "no-carddata":
        (out / ".gitkeep").touch()
        return
    if variant == "empty":
        cards = []
    (out / "carddata.txt").write_bytes("".join(line + "\n" for line in cards).encode("ascii"))


def main(argv):
    if len(argv) in (3, 4) and argv[1] == "edge" and (len(argv) == 3 or argv[3] in ("full", "empty", "no-carddata")):
        write(argv[2], edge(), argv[3] if len(argv) == 4 else "full")
    elif len(argv) == 4 and argv[1] == "random":
        write(argv[3], random_batch(int(argv[2])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
