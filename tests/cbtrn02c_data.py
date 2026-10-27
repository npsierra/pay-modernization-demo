#!/usr/bin/env python3
"""Builds CBTRN02C input files (dailytran.txt, cardxref.txt, acctdata.txt, tcatbal.txt)
in the same ASCII layout as app/data/ASCII.

  cbtrn02c_data.py edge OUT_DIR           hand-made cases for every reject reason and boundary
  cbtrn02c_data.py random SEED OUT_DIR    a random batch (same seed = same files)
  cbtrn02c_data.py intcalc SEED OUT_DIR   random CBACT04C input (tcatbal, acctdata, cardxref, discgrp)
"""
import random
import sys
from decimal import Decimal
from pathlib import Path

POSITIVE = "{ABCDEFGHI"
NEGATIVE = "}JKLMNOPQR"


def zoned(value, integer_digits):
    """Signed DISPLAY number with an overpunch sign, e.g. -12.30 -> '0000000123}'."""
    cents = int((Decimal(value) * 100).to_integral_value())
    digits = f"{abs(cents):0{integer_digits + 2}d}"
    if len(digits) > integer_digits + 2:
        raise ValueError(f"{value} does not fit {integer_digits} integer digits")
    sign = NEGATIVE if cents < 0 else POSITIVE
    return digits[:-1] + sign[int(digits[-1])]


def fixed(text, width):
    text = str(text)
    if len(text) > width:
        raise ValueError(f"{text!r} longer than {width}")
    return text.ljust(width)


def daily(tran_id, type_cd, cat_cd, amount, card, orig_date, desc="Edge case"):
    record = (fixed(f"{tran_id:016d}", 16) + type_cd + f"{cat_cd:04d}" + fixed("POS TERM", 10)
              + fixed(desc, 100) + zoned(amount, 9) + "800000000" + fixed("Test Merchant", 50)
              + fixed("Testville", 50) + fixed("12345", 10) + card
              + fixed(f"{orig_date} 10:00:00.000000", 26) + " " * 26 + " " * 20)
    assert len(record) == 350, len(record)
    return record


def xref(card, customer_id, acct_id):
    return card + f"{customer_id:09d}" + f"{acct_id:011d}"


def account(acct_id, limit, expiration, balance=0, cycle_credit=0, cycle_debit=0, group=""):
    record = (f"{acct_id:011d}" + "Y" + zoned(balance, 10) + zoned(limit, 10) + zoned(Decimal(limit) / 2, 10)
              + "2015-01-01" + expiration + expiration + zoned(cycle_credit, 10) + zoned(cycle_debit, 10)
              + "A000000000" + fixed(group, 10))
    return fixed(record, 300)


def tcatbal(acct_id, type_cd, cat_cd, balance, filler="0" * 22):
    return f"{acct_id:011d}" + type_cd + f"{cat_cd:04d}" + zoned(balance, 9) + fixed(filler, 22)


def card_number(n):
    return f"{4000000000000000 + n:016d}"


def edge():
    """Each case is commented with the expected outcome under the original rules."""
    c1, c2, c3, c4, c5, c6 = (card_number(n) for n in range(1, 7))
    accounts = [
        account(901, "1000.00", "2030-12-31"),
        account(902, "5000.00", "2026-06-15"),
        account(904, "1000.00", "2030-12-31"),
        # Cycle credit at the top of PIC S9(10)V99: tests how both versions handle overflow.
        account(905, "500.00", "2030-12-31", cycle_credit="9999999999.00", balance="9999999999.00"),
        # Credit already over the limit before the batch.
        account(906, "100.00", "2030-12-31", cycle_credit="150.00", balance="150.00"),
    ]
    xrefs = [xref(c1, 1, 901), xref(c2, 2, 902), xref(c3, 3, 999), xref(c4, 4, 904),
             xref(c5, 5, 905), xref(c6, 6, 906)]
    tcatbals = [
        tcatbal(904, "01", 1, "100.00", "FILLER-FROM-904-01-01"),
        tcatbal(904, "03", 1, "-5.00", "FILLER-FROM-904-03-01"),
        tcatbal(902, "01", 1, "0.00"),
        tcatbal(905, "01", 1, "0.00"),
        tcatbal(906, "01", 1, "0.00"),
    ]
    t = iter(range(1, 10_000))
    trans = [
        # First record in the batch creates a category balance before any successful read
        # (new record's FILLER is spaces), then posts.
        daily(next(t), "01", 1, "600.00", c1, "2026-01-10", "901 posted, creates TCATBAL"),
        daily(next(t), "01", 1, "400.00", c1, "2026-01-10", "901 posted exactly at limit"),
        daily(next(t), "01", 1, "0.01", c1, "2026-01-10", "901 102 one cent over"),
        daily(next(t), "03", 1, "-50.00", c1, "2026-01-10", "901 return posted, creates TCATBAL"),
        daily(next(t), "01", 1, "0.01", c1, "2026-01-10", "901 102 the return counts against limit"),
        daily(next(t), "03", 1, "-10.00", c1, "2026-01-10", "901 102 a return rejected as over limit"),
        daily(next(t), "01", 1, "100.00", c2, "2026-06-15", "902 posted on expiration day"),
        daily(next(t), "01", 1, "100.00", c2, "2026-06-16", "902 103 day after expiration"),
        daily(next(t), "01", 1, "9000.00", c2, "2026-06-16", "902 103 wins over 102"),
        daily(next(t), "01", 1, "9000.00", c2, "2026-06-01", "902 102 over limit, not expired"),
        daily(next(t), "01", 1, "0.00", c2, "2026-06-01", "902 zero amount posted to cycle credit"),
        daily(next(t), "02", 2, "-25.00", c2, "2026-06-01", "902 type 02 negative, creates TCATBAL"),
        daily(next(t), "01", 1, "10.00", c3, "2026-01-10", "101 card's account missing"),
        daily(next(t), "01", 1, "10.00", card_number(99), "2026-01-10", "100 unknown card"),
        daily(next(t), "01", 1, "10.00", c4, "2026-01-10", "904 posted, updates TCATBAL 01/0001"),
        daily(next(t), "01", 7, "10.00", c4, "2026-01-10", "904 creates 01/0007 after 01/0001 read"),
        daily(next(t), "03", 1, "-1.00", c4, "2026-01-10", "904 return updates TCATBAL 03/0001"),
        daily(next(t), "01", 8, "1.00", c4, "2026-01-10", "904 creates 01/0008 after 03/0001 read"),
        daily(next(t), "01", 1, "5.00", c5, "2026-01-10", "905 credit overflows S9(10)V99"),
        daily(next(t), "01", 1, "1.00", c6, "2026-01-10", "906 102 already over limit"),
        daily(next(t), "03", 1, "-200.00", c6, "2026-01-10", "906 big return posted"),
        daily(next(t), "01", 1, "999999999.99", c1, "2026-01-10", "901 102 largest amount"),
        daily(next(t), "03", 1, "-999999999.99", c4, "2026-01-10", "904 most negative amount posted"),
    ]
    return trans, xrefs, accounts, tcatbals


def random_batch(seed):
    rng = random.Random(seed)

    def money(low, high):
        return Decimal(rng.randint(int(low * 100), int(high * 100))) / 100

    def date():
        return f"{rng.randint(2022, 2027)}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}"

    n_accounts = rng.randint(3, 20)
    acct_ids = rng.sample(range(1, 100_000), n_accounts)
    accounts = []
    terms = {}
    for acct_id in acct_ids:
        if rng.random() < 0.05:
            credit = money(9_999_000_000, 9_999_999_999)
        else:
            credit = money(0, 5_000)
        expiration = date() if rng.random() < 0.2 else f"{rng.randint(2028, 2035)}-12-31"
        limit, debit = money(0, 50_000), -money(0, 1_000)
        terms[acct_id] = (limit - credit + debit, expiration)
        accounts.append(account(acct_id, limit, expiration, balance=money(-5_000, 5_000),
                                cycle_credit=credit, cycle_debit=debit))
    cards = {}
    for acct_id in acct_ids + rng.sample(range(100_000, 200_000), rng.randint(0, 1)):
        for _ in range(rng.randint(1, 2) if acct_id < 100_000 else 1):
            cards[card_number(rng.randint(0, 10**15))] = acct_id
    xrefs = [xref(card, i, acct_id) for i, (card, acct_id) in enumerate(cards.items())]
    card_list = list(cards)
    unknown = [card_number(10**15 + n) for n in range(3)]

    tcatbals = {}
    for acct_id in acct_ids:
        for _ in range(rng.randint(0, 3)):
            key = (acct_id, rng.choice(["01", "02", "03"]), rng.randint(1, 5))
            filler = "".join(rng.choice("0123456789ABCXYZ -") for _ in range(22))
            tcatbals[key] = tcatbal(*key, money(-10_000, 10_000), filler)

    trans = []
    for tran_id in rng.sample(range(1, 10**12), rng.randint(1, 300)):
        type_cd = rng.choice(["01", "01", "01", "02", "03"])
        roll = rng.random()
        if roll < 0.05:
            amount = Decimal(0)
        elif roll < 0.08:
            amount = money(-999_999_999.99, 999_999_999.99)
        elif type_cd == "01":
            amount = money(0.01, 3_000)
        else:
            amount = -money(0.01, 1_500)
        card = rng.choice(unknown) if rng.random() < 0.03 else rng.choice(card_list)
        tran_date = date()
        if cards.get(card) in terms:
            # Aim some transactions at the boundaries: the amount that lands exactly on the
            # account's limit (before this batch's postings), one cent over, and the expiration
            # date itself or the day after.
            headroom, expiration = terms[cards[card]]
            roll = rng.random()
            if roll < 0.08 and abs(headroom) < 999_999_999:
                amount = headroom
            elif roll < 0.12 and abs(headroom) < 999_999_999:
                amount = headroom + Decimal("0.01")
            if rng.random() < 0.1:
                tran_date = expiration
            elif rng.random() < 0.1:
                tran_date = expiration[:8] + f"{min(int(expiration[8:]) + 1, 29):02d}"
        trans.append(daily(tran_id, type_cd, rng.randint(1, 5), amount, card, tran_date, "Random"))
    return trans, xrefs, accounts, list(tcatbals.values())


def intcalc_batch(seed):
    """Random CBACT04C input. Every category balance's account has an account record and a
    card (a missing one makes the COBOL abend). Groups mix known, unknown (falls back to
    DEFAULT) and blank; rates include zero, so some balances get no interest."""
    rng = random.Random(seed)

    def money(low, high):
        return Decimal(rng.randint(int(low * 100), int(high * 100))) / 100

    groups = ["GOLD", "SILVER", "ZEROAPR", "NOSUCHGRP", ""]
    discgrp = []
    for group in ["DEFAULT", "GOLD", "SILVER", "ZEROAPR"]:
        for type_cd in ["01", "02", "03"]:
            for cat_cd in range(1, 6):
                if group != "DEFAULT" and rng.random() < 0.3:
                    continue
                rate = Decimal(0) if group == "ZEROAPR" or rng.random() < 0.15 else money(-5, 99.99)
                discgrp.append(fixed(fixed(group, 10) + type_cd + f"{cat_cd:04d}" + zoned(rate, 4), 50))
    acct_ids = sorted(rng.sample(range(1, 100_000), rng.randint(1, 20)))
    accounts, xrefs, tcatbals = [], [], []
    for acct_id in acct_ids:
        big = rng.random() < 0.05
        accounts.append(account(acct_id, money(0, 50_000), "2030-12-31",
                                balance=money(-9_999_999_999, 9_999_999_999) if big else money(-5_000, 5_000),
                                cycle_credit=money(0, 5_000), cycle_debit=-money(0, 1_000),
                                group=rng.choice(groups)))
        for _ in range(rng.randint(1, 3)):
            xrefs.append(xref(card_number(rng.randint(0, 10**15)), acct_id, acct_id))
        for type_cd in ["01", "02", "03"]:
            for cat_cd in range(1, 6):
                if rng.random() < 0.3:
                    balance = money(-999_999_999.99, 999_999_999.99) if big else money(-20_000, 20_000)
                    tcatbals.append(tcatbal(acct_id, type_cd, cat_cd, balance))
    return tcatbals, xrefs, accounts, discgrp


def write_intcalc(out_dir, tcatbals, xrefs, accounts, discgrp):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for name, lines in (("tcatbal.txt", tcatbals), ("cardxref.txt", xrefs), ("acctdata.txt", accounts),
                        ("discgrp.txt", discgrp)):
        (out / name).write_text("".join(line + "\n" for line in sorted(lines)), encoding="ascii")


def write(out_dir, trans, xrefs, accounts, tcatbals):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    # Indexed files are loaded like IDCAMS REPRO into an empty KSDS: keys must be ascending.
    for name, lines in (("dailytran.txt", trans), ("cardxref.txt", sorted(xrefs)),
                        ("acctdata.txt", sorted(accounts)), ("tcatbal.txt", sorted(tcatbals))):
        (out / name).write_text("".join(line + "\n" for line in lines), encoding="ascii")


def main(argv):
    if len(argv) == 3 and argv[1] == "edge":
        write(argv[2], *edge())
    elif len(argv) == 4 and argv[1] == "random":
        write(argv[3], *random_batch(int(argv[2])))
    elif len(argv) == 4 and argv[1] == "intcalc":
        write_intcalc(argv[3], *intcalc_batch(int(argv[2])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
