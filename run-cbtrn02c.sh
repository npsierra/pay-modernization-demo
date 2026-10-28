#!/usr/bin/env bash
# Compile and run the CardDemo daily transaction posting batch (CBTRN02C)
# off-mainframe with GnuCOBOL, mirroring app/jcl/POSTTRAN.jcl.
# Every run rebuilds the input files from app/data/ASCII (or INPUT_DIR), so runs are repeatable.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${INPUT_DIR:-$ROOT/app/data/ASCII}"
WORK="${WORK_DIR:-$ROOT/gnucobol/work}"
# Sign convention used to read signed amounts: EBCDIC (correct for this data) or ASCII
# (reproduces the misread you get without the flag -- 299 of 300 rejected instead of 38 on GnuCOBOL 3.2).
SIGN_ENCODING="${SIGN_ENCODING:-EBCDIC}"
BIN="$WORK/bin"
DD="$WORK/data"

case "$SIGN_ENCODING" in EBCDIC|ASCII) ;; *) echo "SIGN_ENCODING must be EBCDIC or ASCII" >&2; exit 8 ;; esac
[ "$(cd "$WORK" 2>/dev/null && pwd || echo "$WORK")" != "/" ] || { echo "WORK_DIR must not be /" >&2; exit 8; }

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL (Ubuntu: sudo apt-get install -y gnucobol, macOS: brew install gnucobol)" >&2; exit 1; }

rm -rf "$BIN" "$DD"
rm -f "$WORK/sysout.txt" "$WORK/rejects.txt"
mkdir -p "$BIN" "$DD"

echo "== Compile"
# -fsign=EBCDIC (default): the sample data stores signed amounts with mainframe overpunch
# signs ('{' = +0, 'A'..'I' = +1..+9, '}' = -0, 'J'..'R' = -1..-9).
cobc -x -fsign="$SIGN_ENCODING" -I "$ROOT/app/cpy" -o "$BIN/CBTRN02C" "$ROOT/app/cbl/CBTRN02C.cbl"
# Built in $BIN so cobc picks the platform module extension (.so on Linux, .dylib on macOS).
(cd "$BIN" && cobc -m "$ROOT/gnucobol/CEE3ABD.cbl")
cobc -x -o "$BIN/KSDSUTIL" "$ROOT/gnucobol/KSDSUTIL.cbl"
# STEPLIB equivalent: where dynamic CALLs (CEE3ABD) are resolved.
export COB_LIBRARY_PATH="$BIN"

echo "== Prepare data (stand-in for the IDCAMS DEFINE/REPRO jobs)"
# DALYTRAN is a fixed 350-byte sequential file: drop the line feeds.
tr -d '\r\n' < "$DATA/dailytran.txt" > "$DD/DALYTRAN.dat"
# KSDS files: load text into GnuCOBOL indexed files (CR stripped; short lines space padded).
load_ksds() { # <type> <source.txt> <target>
  tr -d '\r' < "$DATA/$2" > "$DD/$1.txt"
  DD_TEXTFILE="$DD/$1.txt" DD_KSDSFILE="$DD/$3" "$BIN/KSDSUTIL" LOAD "$1"
}
load_ksds XREF    cardxref.txt CARDXREF.idx
load_ksds ACCT    acctdata.txt ACCTDATA.idx
load_ksds TCATBAL tcatbal.txt  TCATBALF.idx
DD_TEXTFILE="$DD/ACCTDATA.before.txt" DD_KSDSFILE="$DD/ACCTDATA.idx" "$BIN/KSDSUTIL" UNLOAD ACCT >/dev/null

echo "== Run CBTRN02C (POSTTRAN STEP15)"
# DD statements -> DD_<name> environment variables.
export DD_DALYTRAN="$DD/DALYTRAN.dat"
export DD_TRANFILE="$DD/TRANSACT.idx"
export DD_XREFFILE="$DD/CARDXREF.idx"
export DD_DALYREJS="$DD/DALYREJS.dat"
export DD_ACCTFILE="$DD/ACCTDATA.idx"
export DD_TCATBALF="$DD/TCATBALF.idx"
set +e
"$BIN/CBTRN02C" | tee "$WORK/sysout.txt"
RC=${PIPESTATUS[0]}
set -e
echo "CBTRN02C return code: $RC"
# RC 4 = completed with rejects (as on the mainframe); anything higher is a failure.
if [ "$RC" -gt 4 ]; then exit "$RC"; fi

echo "== Results"
unload() { DD_TEXTFILE="$DD/$1" DD_KSDSFILE="$DD/$2" "$BIN/KSDSUTIL" UNLOAD "$3" >/dev/null; }
unload ACCTDATA.after.txt ACCTDATA.idx ACCT
unload TCATBALF.after.txt TCATBALF.idx TCATBAL
unload TRANSACT.after.txt TRANSACT.idx TRAN
fold -w 430 "$DD/DALYREJS.dat" > "$WORK/rejects.txt" 2>/dev/null || : > "$WORK/rejects.txt"
echo "Transactions posted to TRANSACT: $(wc -l < "$DD/TRANSACT.after.txt" | tr -d " ")"
echo "Rejects by reason (code + description):"
cut -c351-430 "$WORK/rejects.txt" | sed 's/ *$//' | sort | uniq -c
echo "Accounts updated: $(diff "$DD/ACCTDATA.before.txt" "$DD/ACCTDATA.after.txt" | grep -c '^>' || true)"
echo "Category balance records: $(wc -l < "$DD/TCATBAL.txt" | tr -d " ") before, $(wc -l < "$DD/TCATBALF.after.txt" | tr -d " ") after"
echo "Outputs in $WORK (sysout.txt, rejects.txt, data/*.after.txt)"
exit "$RC"
