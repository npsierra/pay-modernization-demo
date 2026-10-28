#!/usr/bin/env bash
# Compile and run the CardDemo account file extract (CBACT01C) off-mainframe with GnuCOBOL,
# mirroring app/jcl/READACCT.jcl STEP05. Input acctdata.txt comes from app/data/ASCII or
# INPUT_DIR; a missing acctdata.txt leaves ACCTFILE undefined (the program abends, rc 16).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${INPUT_DIR:-$ROOT/app/data/ASCII}"
WORK="${WORK_DIR:-$ROOT/gnucobol/work-cbact01c}"
BIN="$WORK/bin"
DD="$WORK/data"

[ "$(cd "$WORK" 2>/dev/null && pwd || echo "$WORK")" != "/" ] || { echo "WORK_DIR must not be /" >&2; exit 8; }

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL (Ubuntu: sudo apt-get install -y gnucobol, macOS: brew install gnucobol)" >&2; exit 1; }

rm -rf "$BIN" "$DD"
rm -f "$WORK/sysout.txt"
mkdir -p "$BIN" "$DD"

echo "== Compile"
# -fsign=EBCDIC: signed amounts in the data use mainframe overpunch signs.
cobc -x -fsign=EBCDIC -I "$ROOT/app/cpy" -o "$BIN/CBACT01C" "$ROOT/app/cbl/CBACT01C.cbl"
# Stand-ins for the assembler date routine (app/asm/COBDATFT.asm) and the LE abend service.
(cd "$BIN" && cobc -m "$ROOT/gnucobol/COBDATFT.cbl" && cobc -m "$ROOT/gnucobol/CEE3ABD.cbl")
cobc -x -o "$BIN/KSDSUTIL" "$ROOT/gnucobol/KSDSUTIL.cbl"
export COB_LIBRARY_PATH="$BIN"

echo "== Prepare data (stand-in for the IDCAMS DEFINE/REPRO jobs)"
if [ -f "$DATA/acctdata.txt" ]; then
  tr -d '\r' < "$DATA/acctdata.txt" > "$DD/ACCT.txt"
  DD_TEXTFILE="$DD/ACCT.txt" DD_KSDSFILE="$DD/ACCTDATA.idx" "$BIN/KSDSUTIL" LOAD ACCT
else
  echo "no acctdata.txt in $DATA: ACCTFILE will not exist"
fi

echo "== Run CBACT01C (READACCT STEP05)"
export DD_ACCTFILE="$DD/ACCTDATA.idx"
export DD_OUTFILE="$DD/OUTFILE.dat"    # RECFM=FB LRECL=107
export DD_ARRYFILE="$DD/ARRYFILE.dat"  # RECFM=FB LRECL=110
export DD_VBRCFILE="$DD/VBRCFILE.dat"  # RECFM=VB LRECL=84
# Variable-length records: 2-byte big-endian data length + 2 zero bytes before each record
# (GnuCOBOL's default, pinned here; a z/OS RDW holds data length + 4 in the same layout).
export COB_VARSEQ_FORMAT=0
set +e
"$BIN/CBACT01C" | tee "$WORK/sysout.txt"
RC=${PIPESTATUS[0]}
set -e
echo "CBACT01C return code: $RC"
if [ "$RC" -ne 0 ]; then exit "$RC"; fi

echo "== Results"
size() { wc -c < "$1" | tr -d ' '; }
vb_records() { od -An -v -tu1 "$1" | awk '{ for (i = 1; i <= NF; i++) b[n++] = $i }
  END { while (p + 4 <= n) { c++; p += 4 + b[p] * 256 + b[p + 1] } print c + 0 }'; }
echo "Accounts read (ACCTFILE): $(grep -c '^ACCT-ID  ' "$WORK/sysout.txt" || true)"
echo "OUTFILE records (107 bytes): $(( $(size "$DD/OUTFILE.dat") / 107 ))"
echo "ARRYFILE records (110 bytes): $(( $(size "$DD/ARRYFILE.dat") / 110 ))"
echo "VBRCFILE records (12 and 39 bytes): $(vb_records "$DD/VBRCFILE.dat")"
echo "Outputs in $WORK (sysout.txt, data/OUTFILE.dat, data/ARRYFILE.dat, data/VBRCFILE.dat)"
echo "Readable view: tests/cbact01c_view.py $DD"
exit "$RC"
