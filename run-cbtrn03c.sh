#!/usr/bin/env bash
# Compile and run the CardDemo transaction detail report (CBTRN03C) off-mainframe with
# GnuCOBOL, mirroring app/jcl/TRANREPT.jcl.
# Input files (INPUT_DIR): transact.txt (transaction master unload, 350 bytes), cardxref.txt,
# trantype.txt, trancatg.txt, dateparm.txt ('YYYY-MM-DD YYYY-MM-DD'). tranfile.txt, if
# present, is used as TRANFILE as is and the SORT step is skipped.
# Default (no INPUT_DIR): CBTRN02C's saved posted transactions (tests/golden/cbtrn02c), the
# app/data/ASCII lookups and DATEPARM_DATES (the posting day of that baseline).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK_DIR:-$ROOT/gnucobol/work-cbtrn03c}"
DATEPARM_DATES="${DATEPARM_DATES:-2026-10-27 2026-10-27}"
BIN="$WORK/bin"
DD="$WORK/data"

[ "$(cd "$WORK" 2>/dev/null && pwd || echo "$WORK")" != "/" ] || { echo "WORK_DIR must not be /" >&2; exit 8; }

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL (Ubuntu: gnucobol/install-gnucobol.sh, macOS: brew install gnucobol)" >&2; exit 1; }

rm -rf "$BIN" "$DD"
rm -f "$WORK/sysout.txt" "$WORK/TRANREPT.txt"
mkdir -p "$BIN" "$DD"

if [ -n "${INPUT_DIR:-}" ]; then
  DATA="$INPUT_DIR"
else
  DATA="$WORK/input"
  rm -rf "$DATA"
  mkdir -p "$DATA"
  cp "$ROOT/tests/golden/cbtrn02c/TRANSACT.txt" "$DATA/transact.txt"
  cp "$ROOT/app/data/ASCII/cardxref.txt" "$ROOT/app/data/ASCII/trantype.txt" \
     "$ROOT/app/data/ASCII/trancatg.txt" "$DATA/"
  printf '%s\n' "$DATEPARM_DATES" > "$DATA/dateparm.txt"
fi

echo "== Compile"
cobc -x -fsign=EBCDIC -I "$ROOT/app/cpy" -o "$BIN/CBTRN03C" "$ROOT/app/cbl/CBTRN03C.cbl"
(cd "$BIN" && cobc -m "$ROOT/gnucobol/CEE3ABD.cbl")
cobc -x -o "$BIN/KSDSUTIL" "$ROOT/gnucobol/KSDSUTIL.cbl"
export COB_LIBRARY_PATH="$BIN"

echo "== Prepare data (stand-in for the IDCAMS REPRO and SORT steps)"
# Fixed-length sequential records: CR stripped, empty lines dropped, padded/cut to length.
to_fixed() { # <length> <source> <target>
  tr -d '\r' < "$2" | awk -v n="$1" 'length($0) > 0 { printf "%-" n "." n "s", $0 }' > "$3"
}
# A missing source leaves the DD's file missing, so the program's OPEN fails as it would.
if [ -f "$DATA/dateparm.txt" ]; then
  to_fixed 80 "$DATA/dateparm.txt" "$DD/DATEPARM.dat"
fi
if [ -f "$DATA/tranfile.txt" ]; then
  to_fixed 350 "$DATA/tranfile.txt" "$DD/TRANFILE.dat"
elif [ -f "$DATA/transact.txt" ]; then
  # TRANREPT STEP05R (DFSORT): INCLUDE processing date (cols 305-314) within the DATEPARM
  # range, SORT FIELDS=(card number cols 263-278,A). Stable, so equal cards keep the
  # unload's transaction-ID order. Without a DATEPARM nothing is filtered.
  START="" END=""
  if [ -f "$DD/DATEPARM.dat" ]; then
    START="$(cut -c1-10 "$DD/DATEPARM.dat")"; END="$(cut -c12-21 "$DD/DATEPARM.dat")"
  fi
  tr -d '\r' < "$DATA/transact.txt" \
    | LC_ALL=C awk -v s="$START" -v e="$END" -v filter="$([ -f "$DD/DATEPARM.dat" ] && echo yes)" '
        length($0) > 0 {
          rec = sprintf("%-350.350s", $0); d = substr(rec, 305, 10)
          if (filter != "yes" || (d "" >= s "" && d "" <= e "")) print rec
        }' \
    | LC_ALL=C sort -s -t "$(printf '\001')" -k1.263,1.278 \
    | awk '{ printf "%s", $0 }' > "$DD/TRANFILE.dat"
fi
load_ksds() { # <type> <source.txt> <target>
  [ -f "$DATA/$2" ] || return 0
  tr -d '\r' < "$DATA/$2" > "$DD/$1.txt"
  DD_TEXTFILE="$DD/$1.txt" DD_KSDSFILE="$DD/$3" "$BIN/KSDSUTIL" LOAD "$1"
}
load_ksds XREF     cardxref.txt CARDXREF.idx
load_ksds TRANTYPE trantype.txt TRANTYPE.idx
load_ksds TRANCATG trancatg.txt TRANCATG.idx

echo "== Run CBTRN03C (TRANREPT STEP10R)"
export DD_TRANFILE="$DD/TRANFILE.dat"
export DD_CARDXREF="$DD/CARDXREF.idx"
export DD_TRANTYPE="$DD/TRANTYPE.idx"
export DD_TRANCATG="$DD/TRANCATG.idx"
export DD_DATEPARM="$DD/DATEPARM.dat"
export DD_TRANREPT="$DD/TRANREPT.dat"
set +e
"$BIN/CBTRN03C" | tee "$WORK/sysout.txt"
RC=${PIPESTATUS[0]}
set -e
echo "CBTRN03C return code: $RC"

# TRANREPT is RECFM=FB LRECL=133: one 133-byte record per line. It is kept after an abend
# (z/OS would delete it: DISP=(NEW,CATLG,DELETE)), so the partial report can be compared.
if [ -f "$DD/TRANREPT.dat" ]; then
  fold -w 133 "$DD/TRANREPT.dat" | awk 1 > "$WORK/TRANREPT.txt"
fi

echo "== Results"
if [ -f "$DD/TRANFILE.dat" ]; then
  echo "Transactions in TRANFILE: $(( $(wc -c < "$DD/TRANFILE.dat") / 350 ))"
fi
if [ -f "$WORK/TRANREPT.txt" ]; then
  echo "Report lines: $(wc -l < "$WORK/TRANREPT.txt" | tr -d " ")"
  echo "Detail lines: $(grep -c '^[0-9A-Za-z]\{16\} ' "$WORK/TRANREPT.txt" || true)"
  echo "Account totals: $(grep -c '^Account Total' "$WORK/TRANREPT.txt" || true)"
  echo "Page totals: $(grep -c '^Page Total' "$WORK/TRANREPT.txt" || true)"
  grep '^Grand Total' "$WORK/TRANREPT.txt" | tr -s '. ' || true
else
  echo "No report written"
fi
echo "Outputs in $WORK (sysout.txt, TRANREPT.txt)"
exit "$RC"
