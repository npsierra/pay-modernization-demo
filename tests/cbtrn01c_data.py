#!/usr/bin/env python3
"""Builds CBTRN01C input files (dailytran, custdata, cardxref, carddata, acctdata and
transact .txt) in the same ASCII layout as app/data/ASCII.

  cbtrn01c_data.py edge OUT_DIR [full|empty|no-dailytran|no-custdata]
                                       hand-made cases for every outcome; the variants give an
                                       empty daily file or leave one input out (abend)
  cbtrn01c_data.py random SEED OUT_DIR a random batch (same seed = same files)
"""
import random
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from cbtrn02c_data import card_number, daily, fixed, xref  # noqa: E402

TEXT = "".join(chr(c) for c in range(32, 127))


def customer(cust_id, name="Test Customer"):
    return fixed(f"{cust_id:09d}" + fixed(name, 25), 500)


def card(number, acct_id):
    return fixed(number + f"{acct_id:011d}" + "123" + fixed("TEST HOLDER", 50) + "2030-12-31Y", 150)


def account(acct_id, rest="Y"):
    return fixed(f"{acct_id:011d}" + rest, 300)


def transaction(tran_id):
    return fixed(f"{tran_id:016d}" + "01" + "0001" + "POS TERM  " + "Already posted", 350)


def edge():
    """Each case is commented with the expected SYSOUT lines."""
    c1, c2, c3, c4, c5 = (card_number(n) for n in range(1, 6))
    xrefs = [
        xref(c1, 1, 901),
        xref(c2, 2, 902),
        xref(c3, 3, 999),                     # account 999 is not in acctdata
        xref(c4, 4, 99999999999).rstrip(),    # short line: padded on load; largest account ID
        xref(c5, 5, 905) + " " * 14 + "\r",   # full 50 bytes with a CRLF line ending
    ]
    accounts = [account(901), account(902, "N" + "account text is never looked at"), account(905),
                account(99999999999)]
    t = iter(range(1, 10_000))
    trans = [
        daily(next(t), "01", 1, "10.00", c1, "2026-01-10", "card and account found"),
        daily(next(t), "03", 1, "-5.00", c2, "2026-01-10", "second card, account found"),
        daily(next(t), "01", 1, "1.00", c3, "2026-01-10", "card found, ACCOUNT 00000000999 NOT FOUND"),
        daily(next(t), "01", 1, "1.00", card_number(99), "2026-01-10", "unknown card: COULD NOT BE VERIFIED"),
        daily(next(t), "01", 1, "1.00", " " * 16, "2026-01-10", "blank card number: not verified"),
        daily(next(t), "01", 1, "0.00", c4, "2026-01-10", "short xref line, largest account ID"),
        daily(next(t), "01", 1, "999999999.99", c5, "2026-01-10", "CRLF xref line, largest amount"),
        daily(next(t), "01", 1, "1.00", c1, "2026-01-10", "same card again"),
        # Last record: unknown card. The end-of-file pass looks it up again, so its
        # COULD NOT BE VERIFIED line appears twice.
        daily(next(t), "01", 1, "1.00", card_number(98), "2026-01-10", "last record, unknown card"),
    ]
    trans[1] += "\r"  # CRLF in the daily file too: line ends are dropped
    customers = [customer(n) for n in range(1, 6)]
    cards = [card(c, a) for c, a in ((c1, 901), (c2, 902), (c3, 999), (c4, 99999999999), (c5, 905))]
    return {"dailytran.txt": trans, "custdata.txt": customers, "cardxref.txt": xrefs, "carddata.txt": cards,
            "acctdata.txt": accounts, "transact.txt": [transaction(1), transaction(2)]}


def random_batch(seed):
    """Random CBTRN01C input: cards in and out of the cross-reference, accounts present and
    missing, blank card numbers, sometimes an empty daily file; keyed files sorted by key."""
    rng = random.Random(seed)
    acct_ids = sorted(rng.sample(range(1, 10 ** 11), rng.randint(1, 15)))
    present = [a for a in acct_ids if rng.random() < 0.8]
    xrefs, cards = [], []
    for n in rng.sample(range(10 ** 15), rng.randint(1, 25)):
        number = card_number(n)
        acct = rng.choice(acct_ids)
        xrefs.append((number, xref(number, rng.randint(0, 999_999_999), acct)))
        cards.append(card(number, acct))
    known = [number for number, _ in xrefs]
    unknown = [card_number(10 ** 15 + n) for n in range(5)]
    count = 0 if rng.random() < 0.05 else rng.randint(1, 40)
    trans = []
    for tran_id in rng.sample(range(1, 10 ** 16), count):
        roll = rng.random()
        number = " " * 16 if roll < 0.03 else rng.choice(unknown) if roll < 0.15 else rng.choice(known)
        desc = "".join(rng.choice(TEXT) for _ in range(rng.randint(0, 100)))
        amount = f"{rng.randint(-99_999_999_999, 99_999_999_999) / 100:.2f}"
        trans.append(daily(tran_id, rng.choice(["01", "02", "03"]), rng.randint(1, 5), amount, number,
                           "2026-01-10", desc))
    xref_lines = []
    for _, line in sorted(xrefs):
        roll = rng.random()
        xref_lines.append(line + ("\r" if roll < 0.1 else " " * 14 if roll < 0.5 else ""))
    accounts = [account(a, "".join(rng.choice(TEXT) for _ in range(rng.randint(0, 289)))) for a in present]
    customers = [customer(n) for n in sorted(rng.sample(range(1, 10 ** 9), rng.randint(1, 5)))]
    transact = [transaction(n) for n in sorted(rng.sample(range(1, 10 ** 16), rng.randint(1, 5)))]
    return {"dailytran.txt": trans, "custdata.txt": customers, "cardxref.txt": xref_lines,
            "carddata.txt": sorted(cards), "acctdata.txt": accounts, "transact.txt": transact}


def write(out_dir, files):
    """One record per line; records ending in '\\r' are written with CRLF line endings."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for name, lines in files.items():
        (out / name).write_bytes("".join(line + "\n" for line in lines).encode("ascii"))


def main(argv):
    if len(argv) in (3, 4) and argv[1] == "edge":
        files = edge()
        variant = argv[3] if len(argv) == 4 else "full"
        if variant == "empty":
            files["dailytran.txt"] = []
        elif variant in ("no-dailytran", "no-custdata"):
            del files[variant[3:] + ".txt"]
        elif variant != "full":
            sys.exit(__doc__)
        write(argv[2], files)
    elif len(argv) == 4 and argv[1] == "random":
        write(argv[3], random_batch(int(argv[2])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
