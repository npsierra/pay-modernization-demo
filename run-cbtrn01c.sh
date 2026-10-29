#!/usr/bin/env bash
# Compile and run the CardDemo daily transaction verification (CBTRN01C) off-mainframe with
# GnuCOBOL. No JCL in app/jcl runs this program, so each DD is named after its ASSIGN clause.
# Inputs come from app/data/ASCII or INPUT_DIR (dailytran, custdata, cardxref, carddata,
# acctdata, transact .txt). A missing file leaves its DD pointing at nothing, so the OPEN
# fails with status 35 and the program abends (rc 16), as it would with no dataset.
# TRANFILE: transact.txt in INPUT_DIR, else CBTRN02C's saved posting output
# (tests/golden/cbtrn02c/TRANSACT.txt); TRANSACT_TXT=<file> or TRANSACT_TXT=none overrides.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${INPUT_DIR:-$ROOT/app/data/ASCII}"
WORK="${WORK_DIR:-$ROOT/gnucobol/work-cbtrn01c}"
BIN="$WORK/bin"
DD="$WORK/data"
if [ -z "${TRANSACT_TXT:-}" ]; then
  if [ -f "$DATA/transact.txt" ]; then TRANSACT_TXT="$DATA/transact.txt"; else TRANSACT_TXT="$ROOT/tests/golden/cbtrn02c/TRANSACT.txt"; fi
fi

[ "$(cd "$WORK" 2>/dev/null && pwd -P || echo "$WORK")" != "/" ] || { echo "WORK_DIR must not be /" >&2; exit 8; }

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL (Ubuntu: gnucobol/install-gnucobol.sh, macOS: brew install gnucobol)" >&2; exit 1; }

rm -rf "$BIN" "$DD"
rm -f "$WORK/sysout.txt"
mkdir -p "$BIN" "$DD"

echo "== Compile"
# -fsign=EBCDIC: signed amounts in the data use mainframe overpunch signs.
cobc -x -fsign=EBCDIC -I "$ROOT/app/cpy" -o "$BIN/CBTRN01C" "$ROOT/app/cbl/CBTRN01C.cbl"
(cd "$BIN" && cobc -m "$ROOT/gnucobol/CEE3ABD.cbl")
cobc -x -o "$BIN/KSDSUTIL" "$ROOT/gnucobol/KSDSUTIL.cbl"
export COB_LIBRARY_PATH="$BIN"

echo "== Prepare data (stand-in for the IDCAMS DEFINE/REPRO jobs)"
# DALYTRAN is a fixed 350-byte sequential file: drop the line feeds.
if [ -f "$DATA/dailytran.txt" ]; then
  tr -d '\r\n' < "$DATA/dailytran.txt" > "$DD/DALYTRAN.dat"
else
  echo "no dailytran.txt in $DATA: DALYTRAN will not exist"
fi
load_ksds() { # <type> <source file> <target>
  if [ -f "$2" ]; then
    tr -d '\r' < "$2" > "$DD/$1.txt"
    DD_TEXTFILE="$DD/$1.txt" DD_KSDSFILE="$DD/$3" "$BIN/KSDSUTIL" LOAD "$1"
  else
    echo "no $(basename "$2") in $(dirname "$2"): $3 will not exist"
  fi
}
load_ksds CUST "$DATA/custdata.txt" CUSTDATA.idx
load_ksds XREF "$DATA/cardxref.txt" CARDXREF.idx
load_ksds CARD "$DATA/carddata.txt" CARDDATA.idx
load_ksds ACCT "$DATA/acctdata.txt" ACCTDATA.idx
if [ "$TRANSACT_TXT" = none ]; then
  echo "TRANSACT_TXT=none: TRANSACT will not exist"
else
  load_ksds TRAN "$TRANSACT_TXT" TRANSACT.idx
fi

echo "== Run CBTRN01C"
# DD statements -> DD_<name> environment variables.
export DD_DALYTRAN="$DD/DALYTRAN.dat"
export DD_CUSTFILE="$DD/CUSTDATA.idx"
export DD_XREFFILE="$DD/CARDXREF.idx"
export DD_CARDFILE="$DD/CARDDATA.idx"
export DD_ACCTFILE="$DD/ACCTDATA.idx"
export DD_TRANFILE="$DD/TRANSACT.idx"
set +e
"$BIN/CBTRN01C" | tee "$WORK/sysout.txt"
RC=${PIPESTATUS[0]}
set -e
echo "CBTRN01C return code: $RC"
if [ "$RC" -ne 0 ]; then exit "$RC"; fi

echo "== Results"
count() { grep -c "$1" "$WORK/sysout.txt" || true; }
echo "Daily transactions read: $(( $(wc -c < "$DD/DALYTRAN.dat") / 350 ))"
echo "Card lookups (one extra for the last record at end of file): $(( $(count '^SUCCESSFUL READ OF XREF') + $(count '^INVALID CARD NUMBER FOR XREF') ))"
echo "Cards not verified (skipped): $(count 'COULD NOT BE VERIFIED')"
echo "Accounts found: $(count '^SUCCESSFUL READ OF ACCOUNT FILE'), not found: $(count 'NOT FOUND$')"
echo "Files written: none (the program only reads and reports)"
echo "Output in $WORK/sysout.txt"
exit "$RC"
