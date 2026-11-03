#!/usr/bin/env bash
# Compile and run the CardDemo card file listing (CBACT02C) off-mainframe with GnuCOBOL,
# mirroring app/jcl/READCARD.jcl STEP05. Input carddata.txt comes from app/data/ASCII or
# INPUT_DIR; a missing carddata.txt leaves CARDFILE undefined (the OPEN fails with status 35
# and the program abends, rc 16). The program writes no files: SYSOUT is its only output.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${INPUT_DIR:-$ROOT/app/data/ASCII}"
WORK="${WORK_DIR:-$ROOT/gnucobol/work-cbact02c}"
BIN="$WORK/bin"
DD="$WORK/data"

[ "$(cd "$WORK" 2>/dev/null && pwd -P || echo "$WORK")" != "/" ] || { echo "WORK_DIR must not be /" >&2; exit 8; }

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL (Ubuntu: gnucobol/install-gnucobol.sh, macOS: brew install gnucobol)" >&2; exit 1; }

rm -rf "$BIN" "$DD"
rm -f "$WORK/sysout.txt"
mkdir -p "$BIN" "$DD"

echo "== Compile"
# -fsign=EBCDIC: signed amounts in the data use mainframe overpunch signs.
cobc -x -fsign=EBCDIC -I "$ROOT/app/cpy" -o "$BIN/CBACT02C" "$ROOT/app/cbl/CBACT02C.cbl"
# Stand-in for the LE abend service.
(cd "$BIN" && cobc -m "$ROOT/gnucobol/CEE3ABD.cbl")
cobc -x -o "$BIN/KSDSUTIL" "$ROOT/gnucobol/KSDSUTIL.cbl"
export COB_LIBRARY_PATH="$BIN"

echo "== Prepare data (stand-in for the IDCAMS DEFINE/REPRO jobs)"
if [ -f "$DATA/carddata.txt" ]; then
  tr -d '\r' < "$DATA/carddata.txt" > "$DD/CARD.txt"
  DD_TEXTFILE="$DD/CARD.txt" DD_KSDSFILE="$DD/CARDDATA.idx" "$BIN/KSDSUTIL" LOAD CARD
else
  echo "no carddata.txt in $DATA: CARDFILE will not exist"
fi

echo "== Run CBACT02C (READCARD STEP05)"
# DD statements -> DD_<name> environment variables.
export DD_CARDFILE="$DD/CARDDATA.idx"
set +e
"$BIN/CBACT02C" | tee "$WORK/sysout.txt"
RC=${PIPESTATUS[0]}
set -e
echo "CBACT02C return code: $RC"
if [ "$RC" -ne 0 ]; then exit "$RC"; fi

echo "== Results"
echo "Cards listed (CARDFILE): $(grep -vc "^START OF EXECUTION\|^END OF EXECUTION" "$WORK/sysout.txt" || true)"
echo "Files written: none (the program only lists the card file)"
echo "Output in $WORK/sysout.txt"
exit "$RC"
