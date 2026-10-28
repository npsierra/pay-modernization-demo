#!/usr/bin/env bash
# Runs the first two jobs of the CardDemo nightly batch back to back, as on the mainframe:
# POSTTRAN (CBTRN02C) posts the daily transactions, then INTCALC (CBACT04C) charges interest
# on the category balances and accounts POSTTRAN just updated.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${INPUT_DIR:-$ROOT/app/data/ASCII}"
WORK="${WORK_DIR:-$ROOT/gnucobol/work-chain}"
mkdir -p "$WORK"

set +e
INPUT_DIR="$DATA" WORK_DIR="$WORK/cbtrn02c" "$ROOT/run-cbtrn02c.sh"
RC=$?
set -e
if [ "$RC" -gt 4 ]; then exit "$RC"; fi

# INTCALC reads the files POSTTRAN updated, plus the unchanged cross-reference and
# disclosure groups.
CHAIN="$WORK/cbact04c-input"
rm -rf "$CHAIN" && mkdir -p "$CHAIN"
cp "$WORK/cbtrn02c/data/TCATBALF.after.txt" "$CHAIN/tcatbal.txt"
cp "$WORK/cbtrn02c/data/ACCTDATA.after.txt" "$CHAIN/acctdata.txt"
cp "$DATA/cardxref.txt" "$DATA/discgrp.txt" "$CHAIN/"

echo
INPUT_DIR="$CHAIN" WORK_DIR="$WORK/cbact04c" "$ROOT/run-cbact04c.sh"
