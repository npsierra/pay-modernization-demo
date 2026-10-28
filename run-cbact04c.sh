#!/usr/bin/env bash
# Compile and run the CardDemo interest calculation batch (CBACT04C) off-mainframe with
# GnuCOBOL, mirroring app/jcl/INTCALC.jcl. Inputs (tcatbal.txt, acctdata.txt, cardxref.txt,
# discgrp.txt) come from app/data/ASCII or INPUT_DIR; run-chain.sh feeds it CBTRN02C's output.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${INPUT_DIR:-$ROOT/app/data/ASCII}"
WORK="${WORK_DIR:-$ROOT/gnucobol/work-cbact04c}"
PARM_DATE="${PARM_DATE:-2022071800}"   # INTCALC.jcl: EXEC PGM=CBACT04C,PARM='2022071800'
BIN="$WORK/bin"
DD="$WORK/data"

[ "$(cd "$WORK" 2>/dev/null && pwd || echo "$WORK")" != "/" ] || { echo "WORK_DIR must not be /" >&2; exit 8; }

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL (Ubuntu: sudo apt-get install -y gnucobol, macOS: brew install gnucobol)" >&2; exit 1; }

rm -rf "$BIN" "$DD"
rm -f "$WORK/sysout.txt" "$WORK/SYSTRAN.txt"
mkdir -p "$BIN" "$DD"

echo "== Compile"
# CBACT04C reads its PARM through PROCEDURE DIVISION USING, so it is built as a module and
# started by RUNPARM, which passes the PARM the way z/OS does.
(cd "$BIN" && cobc -m -fsign=EBCDIC -I "$ROOT/app/cpy" "$ROOT/app/cbl/CBACT04C.cbl" \
  && cobc -m "$ROOT/gnucobol/CEE3ABD.cbl")
cobc -x -o "$BIN/RUNPARM" "$ROOT/gnucobol/RUNPARM.cbl"
cobc -x -o "$BIN/KSDSUTIL" "$ROOT/gnucobol/KSDSUTIL.cbl"
export COB_LIBRARY_PATH="$BIN"

echo "== Prepare data (stand-in for the IDCAMS DEFINE/REPRO jobs)"
load_ksds() { # <type> <source.txt> <target>
  tr -d '\r' < "$DATA/$2" > "$DD/$1.txt"
  DD_TEXTFILE="$DD/$1.txt" DD_KSDSFILE="$DD/$3" "$BIN/KSDSUTIL" LOAD "$1"
}
load_ksds TCATBAL tcatbal.txt  TCATBALF.idx
load_ksds XREFAIX cardxref.txt CARDXREF.idx
load_ksds ACCT    acctdata.txt ACCTDATA.idx
load_ksds DISCGRP discgrp.txt  DISCGRP.idx
DD_TEXTFILE="$DD/ACCTDATA.before.txt" DD_KSDSFILE="$DD/ACCTDATA.idx" "$BIN/KSDSUTIL" UNLOAD ACCT >/dev/null

echo "== Run CBACT04C (INTCALC STEP15, PARM='$PARM_DATE')"
export DD_TCATBALF="$DD/TCATBALF.idx"
export DD_XREFFILE="$DD/CARDXREF.idx"
export DD_ACCTFILE="$DD/ACCTDATA.idx"
export DD_DISCGRP="$DD/DISCGRP.idx"
export DD_TRANSACT="$DD/SYSTRAN.dat"
set +e
"$BIN/RUNPARM" CBACT04C "$PARM_DATE" | tee "$WORK/sysout.txt"
RC=${PIPESTATUS[0]}
set -e
echo "CBACT04C return code: $RC"
if [ "$RC" -gt 4 ]; then exit "$RC"; fi

echo "== Results"
DD_TEXTFILE="$DD/ACCTDATA.after.txt" DD_KSDSFILE="$DD/ACCTDATA.idx" "$BIN/KSDSUTIL" UNLOAD ACCT >/dev/null
fold -w 350 "$DD/SYSTRAN.dat" 2>/dev/null | awk 1 > "$WORK/SYSTRAN.txt" || : > "$WORK/SYSTRAN.txt"
echo "Category balances read: $(wc -l < "$DD/TCATBAL.txt" | tr -d " ")"
echo "Interest transactions written (SYSTRAN): $(wc -l < "$WORK/SYSTRAN.txt" | tr -d " ")"
echo "Accounts updated: $(diff "$DD/ACCTDATA.before.txt" "$DD/ACCTDATA.after.txt" | grep -c '^>' || true)"
echo "Outputs in $WORK (sysout.txt, SYSTRAN.txt, data/ACCTDATA.after.txt)"
exit "$RC"
