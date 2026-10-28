#!/usr/bin/env python3
"""Builds CBTRN03C (transaction detail report) input files in the app/data/ASCII layout:
transact.txt (or tranfile.txt, already sorted: the SORT step is skipped), cardxref.txt,
trantype.txt, trancatg.txt and dateparm.txt.

  cbtrn03c_data.py edge DATA_DIR          hand-made cases, one directory each: DATA_DIR/cbtrn03c-<case>
  cbtrn03c_data.py random SEED OUT_DIR    a random batch (same seed = same files)
"""
import random
import shutil
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from cbtrn02c_data import card_number, fixed, xref, zoned  # noqa: E402

LONG_TYPE = "Purchase with a description longer than 15 chars"
LONG_CATG = "Regular Sales Draft, description cut at 29 chars"


def tran(tran_id, type_cd, cat_cd, amount, card, proc_date, source="POS TERM", amount_field=None):
    """One 350-byte TRAN-RECORD; amount_field overrides the zoned amount (e.g. unsigned or -0)."""
    record = (f"{tran_id:016d}" + type_cd + f"{cat_cd:04d}" + fixed(source, 10)
              + fixed("Report edge case", 100) + (amount_field or zoned(amount, 9)) + "800000000"
              + fixed("Test Merchant", 50) + fixed("Testville", 50) + fixed("12345", 10) + card
              + fixed(f"{proc_date}-10.00.00.000000", 26) + fixed(f"{proc_date}-11.00.00.000000", 26)
              + " " * 20)
    assert len(record) == 350, len(record)
    return record


def trantype(type_cd, desc):
    return type_cd + fixed(desc[:50], 50) + "00000000"


def trancatg(type_cd, cat_cd, desc):
    return type_cd + f"{cat_cd:04d}" + fixed(desc[:50], 50) + "0000"


def lookups():
    """Type and category files: a 50-character description (cut in the report), a CRLF
    line and a short line without the trailing filler."""
    types = [trantype("01", LONG_TYPE), trantype("02", "Payment") + "\r", trantype("03", "Credit"),
             "04Authorization"]
    catgs = [trancatg("01", 1, LONG_CATG), trancatg("01", 2, "Regular Cash Advance") + "\r",
             trancatg("02", 1, "Cash payment"), trancatg("03", 1, "Credit to Account"),
             trancatg("04", 1, "Zero dollar authorization"), "040002Online purchase authorization"]
    return types, catgs


def write(out_dir, trans, xrefs, dateparm, sorted_input=False, types=None, catgs=None):
    """trans/dateparm None = file missing."""
    out = Path(out_dir)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    default_types, default_catgs = lookups()
    files = {"cardxref.txt": sorted(xrefs), "trantype.txt": types or default_types,
             "trancatg.txt": catgs or default_catgs}
    if trans is not None:
        files["tranfile.txt" if sorted_input else "transact.txt"] = trans
    if dateparm is not None:
        files["dateparm.txt"] = [dateparm] if dateparm != "" else []
    for name, lines in files.items():
        (out / name).write_text("".join(line + "\n" for line in lines), encoding="ascii")


def edge(data_dir):
    """Every outcome and boundary of CBTRN03C, as separate input directories."""
    c = [card_number(n) for n in range(1, 10)]
    # c1 and c2 belong to the same account: account totals are per card, not per account.
    xrefs = [xref(c[0], 1, 901), xref(c[1], 1, 901), xref(c[2], 3, 903), xref(c[3], 4, 904),
             xref(c[4], 5, 905), xref(c[5], 6, 906), xref(c[6], 7, 99999999999)]
    parm = "2026-03-01 2026-03-31"
    t = iter(range(1, 10_000))
    trans = [
        tran(next(t), "01", 1, "10.00", c[2], "2026-02-28"),           # day before the range: dropped by SORT
        tran(next(t), "01", 1, "10.01", c[2], "2026-03-01"),           # first day of the range
        tran(next(t), "01", 2, "-25.50", c[0], "2026-03-31"),          # last day of the range
        tran(next(t), "01", 1, "10.02", c[0], "2026-04-01"),           # day after the range: dropped
        tran(next(t), "02", 1, "0", c[1], "2026-03-15"),               # zero amount prints blank
        tran(next(t), "02", 1, "0", c[1], "2026-03-15", amount_field="0000000000}"),  # negative zero
        tran(next(t), "03", 1, "0", c[1], "2026-03-15", amount_field="00000001005"),  # unsigned last digit
        tran(next(t), "04", 2, "-0.01", c[3], "2026-03-15", source="ABCDEFGHIJ"),
        tran(next(t), "01", 1, "999999999.99", c[4], "2026-03-15"),    # largest amount
        tran(next(t), "01", 1, "999999999.99", c[4], "2026-03-15"),    # totals overflow: high digits dropped
        tran(next(t), "01", 1, "-999999999.99", c[5], "2026-03-15"),
        tran(next(t), "01", 1, "1000.00", c[6], "2026-03-15"),         # 11-digit account ID
    ]
    # Same card, transaction IDs out of order: the stable SORT keeps the unload order.
    trans += [tran(9000 - i, "01", 1, f"{i}.{i:02d}", c[3], "2026-03-20") for i in range(1, 4)]
    # A long run on one card so the page break comes from detail lines, then many short
    # accounts so the 2-line account totals make the line counter step past a multiple of 20.
    trans += [tran(next(t), "03", 1, f"-{i}.50", c[2], "2026-03-10") for i in range(1, 24)]
    many = [card_number(100 + n) for n in range(12)]
    xrefs += [xref(card, 100 + n, 1000 + n) for n, card in enumerate(many)]
    trans += [tran(next(t), "01", 2, f"{n + 1}.00", card, "2026-03-05") for n, card in enumerate(many)]
    trans.append(tran(next(t), "01", 1, "765.66", card_number(200), "2026-03-31"))  # last: counted twice
    xrefs.append(xref(card_number(200), 200, 2000))
    out = Path(data_dir)
    write(out / "cbtrn03c-edge", trans, xrefs, parm)
    # Empty transaction file: no headers, no totals, return code 0.
    write(out / "cbtrn03c-empty", [], xrefs, parm)
    # DATEPARM with blank dates and no transactions: the blank processing date of the
    # never-read record is "in range", so a report with only zero totals is printed.
    write(out / "cbtrn03c-blankparm", [], xrefs, " " * 21)
    # Already-sorted input (SORT skipped) with an out-of-range date in the middle: the
    # report stops there silently, without page or grand totals, return code 0.
    unsorted = [tran(1, "01", 1, "1.00", c[0], "2026-03-02"), tran(2, "01", 1, "2.00", c[2], "2026-03-02"),
                tran(3, "01", 1, "3.00", c[0], "2026-03-02"), tran(4, "01", 1, "4.00", c[3], "2026-04-02"),
                tran(5, "01", 1, "5.00", c[3], "2026-03-02")]
    write(out / "cbtrn03c-stop", unsorted, xrefs, parm, sorted_input=True)
    # Lookup failures abend (U0999, return code 16) after writing part of the report.
    good = [tran(1, "01", 1, "1.00", c[0], "2026-03-02"), tran(2, "01", 1, "2.00", c[0], "2026-03-02")]
    write(out / "cbtrn03c-badcard", good + [tran(3, "01", 1, "3.00", card_number(999), "2026-03-02")],
          xrefs, parm)
    write(out / "cbtrn03c-badtype", good + [tran(3, "05", 1, "3.00", c[2], "2026-03-02")], xrefs, parm)
    write(out / "cbtrn03c-badcatg", good + [tran(3, "02", 2, "3.00", c[2], "2026-03-02")], xrefs, parm)
    # Missing files abend at OPEN (status 35): the transaction file (before the report is
    # opened) and the date parameters (after it is opened, so an empty report remains).
    write(out / "cbtrn03c-missing", None, xrefs, parm)
    write(out / "cbtrn03c-noparm", good, xrefs, None)


def random_batch(seed):
    """Random cards, lookups, dates and amounts. About 8% of batches have an unknown card,
    type or category (abend), 5% are empty and 10% use already-sorted input with dates
    outside the range."""
    rng = random.Random(seed)
    words = ["Purchase", "Payment", "Credit", "Authorization", "Refund", "Cash advance fee",
             "A very long transaction description that is cut", "Adjustment"]
    type_codes = sorted(rng.sample([f"{n:02d}" for n in range(1, 20)], rng.randint(1, 6)))
    types = [trantype(code, rng.choice(words)) for code in type_codes]
    categories = sorted({(code, rng.randint(1, 9999)) for code in type_codes for _ in range(rng.randint(1, 4))})
    catgs = [trancatg(code, cat, rng.choice(words) + " " + rng.choice(words)) for code, cat in categories]
    cards = sorted({card_number(rng.randint(0, 10**11)) for _ in range(rng.randint(1, 15))})
    xrefs = [xref(card, i, rng.choice([rng.randint(1, 10**5), 10**11 - 1])) for i, card in enumerate(cards)]
    days = [f"2026-05-{d:02d}" for d in range(1, 11)]
    start = rng.randrange(len(days))
    end = rng.randrange(start, len(days))
    parm = f"{days[start]} {days[end]}"

    def amount():
        kind = rng.random()
        if kind < 0.05:
            return "0"
        if kind < 0.1:
            return rng.choice(["999999999.99", "-999999999.99"])
        if kind < 0.6:
            return f"{rng.randint(-50_000, 50_000) / 100:.2f}"
        return f"{rng.randint(-10**9, 10**9) / 100:.2f}"

    count = 0 if rng.random() < 0.05 else rng.randint(1, 150)
    trans = []
    for n in range(count):
        code, cat = rng.choice(categories)
        trans.append(tran(rng.randint(1, 10**15), code, cat, amount(), rng.choice(cards), rng.choice(days),
                          source=rng.choice(["POS TERM", "OPERATOR", "ABCDEFGHIJ", ""])))
    if trans and rng.random() < 0.08:
        bad = rng.randrange(len(trans))
        record = trans[bad][:304] + fixed(f"{days[start]}-10.00.00.000000", 26) + trans[bad][330:]
        kind = rng.choice(["card", "type", "catg"])
        if kind == "card":
            record = record[:262] + card_number(10**12 + 1) + record[278:]
        elif kind == "type":
            record = record[:16] + "99" + record[18:]
        else:
            record = record[:18] + "0000" + record[22:]
        trans[bad] = record
    sorted_input = rng.random() < 0.1
    if sorted_input:
        trans.sort(key=lambda r: r[262:278])
    return trans, xrefs, parm, sorted_input, types, catgs


def main(argv):
    if len(argv) == 3 and argv[1] == "edge":
        edge(argv[2])
    elif len(argv) == 4 and argv[1] == "random":
        write(argv[3], *random_batch(int(argv[2])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
