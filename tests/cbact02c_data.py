#!/usr/bin/env python3
"""Builds CBACT02C input (carddata.txt) in the same ASCII layout as app/data/ASCII.

  cbact02c_data.py edge OUT_DIR         targeted edge cases, one per line-format and field rule
  cbact02c_data.py random SEED OUT_DIR  a random card file (same seed = same file)

Card numbers come out in ascending order, as KSDSUTIL LOAD needs (status 21 otherwise).
"""
import random
import sys
from pathlib import Path

TEXT = "".join(chr(c) for c in range(32, 127))


def card(number, acct="00000000901", cvv="123", name="TEST HOLDER", expiry="2030-12-31", status="Y"):
    """A full 150-byte card record (CVACT02Y): number, account, CVV, name, expiry, status, filler."""
    return (number + acct + cvv + name.ljust(50) + expiry + status).ljust(150)


def edge():
    """Each line is commented with what it checks; 11 lines load as 12 cards."""
    return [
        "",                                                       # empty line: a card of 150 spaces (lowest key)
        card("0000000000000001", status="N"),                     # inactive card: still listed
        card("4000000000000001", name="Active Holder"),           # ordinary active card
        card("4000000000000002", status=" "),                     # blank status
        "4000000000000003" + "00000000903",                       # short line (27 bytes): padded
        card("4000000000000004", name="CRLF line") + "\r",        # Windows line ending
        card("4000000000000005") + "X" * 60,                      # 210-byte line: cut to 150
        "4000000000000006" + "ABC",                               # 19-byte line
        card("4000000000000007", acct="ABCDEFGHIJK", cvv="X1Y",
             name="o'Brien-Smith, Jr. ~!@#$%^&*()", expiry="31/12/2030", status="?"),
        # 531-byte line: GnuCOBOL reads 500 bytes, then the remaining 31 as a second card.
        card("8888888888888888").ljust(500, "Z") + "9999999999999998" + "LEFT OVER PIECE",
        card("9999999999999999", acct="99999999999", cvv="999", name="Z" * 50, status="Y"),  # highest key
    ]


def random_cards(seed):
    """Random card file: 0-40 cards, random field contents and line formats (short, long, CRLF),
    sometimes a leading empty line or a final line over 500 bytes."""
    rng = random.Random(seed)
    numbers = sorted(rng.sample(range(10 ** 16), rng.randint(0, 40)))
    lines = []
    if rng.random() < 0.1:
        lines.append("")
    for n in numbers:
        acct = f"{rng.randrange(10 ** 11):011d}" if rng.random() < 0.9 else "".join(rng.choices(TEXT, k=11))
        cvv = f"{rng.randrange(1000):03d}" if rng.random() < 0.9 else "".join(rng.choices(TEXT, k=3))
        name = "".join(rng.choices(TEXT, k=rng.randint(0, 50)))
        expiry = f"{rng.randint(2000, 2099)}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}"
        status = rng.choice("YYYYYNN ?")
        line = card(f"{n:016d}", acct, cvv, name, expiry, status)
        if rng.random() < 0.5:
            line = line[:150 - 59]                       # like the sample: filler present or not
        shape = rng.random()
        if shape < 0.1:
            line = line[:rng.randint(16, 90)]            # short line (the key stays whole)
        elif shape < 0.2:
            line += "".join(rng.choices(TEXT, k=rng.randint(1, 499 - len(line))))  # long line, cut to 150
        if rng.random() < 0.1:
            line += "\r"
        lines.append(line)
    if numbers and rng.random() < 0.1:
        # The second 500-byte piece loads as a card whose key ('~...') sorts after every digit.
        lines[-1] = lines[-1].rstrip("\r").ljust(500) + "~" + "".join(rng.choices(TEXT, k=rng.randint(0, 60)))
    return lines


def write(out_dir, lines):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "carddata.txt").write_bytes("".join(line + "\n" for line in lines).encode("ascii"))


def main(argv):
    if len(argv) == 3 and argv[1] == "edge":
        write(argv[2], edge())
    elif len(argv) == 4 and argv[1] == "random":
        write(argv[3], random_cards(int(argv[2])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
