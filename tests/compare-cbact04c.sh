#!/usr/bin/env bash
# Runs CBACT04C (interest calculation) two ways -- the original COBOL under GnuCOBOL and the
# Java port -- and checks that both match a saved baseline of the COBOL results.
# Usage: compare-cbact04c.sh [INPUT_DIR GOLDEN_DIR]
#   default: the chained run -- CBTRN02C's saved output (tests/golden/cbtrn02c ACCTDATA and
#   TCATBALF) plus the sample cardxref/discgrp -- against tests/golden/cbact04c.
# Trailing spaces and the run-time timestamps (SYSTRAN columns 279-330) are ignored.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GOLDEN="$(cd "${2:-$ROOT/tests/golden/cbact04c}" && pwd)"
OUT="$ROOT/build/cbact04c-compare/$(basename "$GOLDEN")"
rm -rf "$OUT"
mkdir -p "$OUT/cobol" "$OUT/java" "$OUT/normalized"
if [ -n "${1:-}" ]; then
  INPUT="$(cd "$1" && pwd)"
else
  INPUT="$OUT/input"
  mkdir -p "$INPUT"
  cp "$ROOT/tests/golden/cbtrn02c/TCATBALF.txt" "$INPUT/tcatbal.txt"
  cp "$ROOT/tests/golden/cbtrn02c/ACCTDATA.txt" "$INPUT/acctdata.txt"
  cp "$ROOT/app/data/ASCII/cardxref.txt" "$ROOT/app/data/ASCII/discgrp.txt" "$INPUT/"
fi

normalize() { # normalize <file> <mask-timestamps: yes|no>
  awk -v mask="$2" '{
    sub(/\r$/, "")
    if (mask == "yes" && length($0) > 278) {
      $0 = sprintf("%-350s", $0)
      $0 = substr($0, 1, 278) sprintf("%52s", "") substr($0, 331)
    }
    sub(/ +$/, "")
    print
  }' "$1"
}

FAILED=0
check() { # check <label> <baseline name> <actual file>
  local mask=no
  [ "$2" = SYSTRAN.txt ] && mask=yes
  normalize "$GOLDEN/$2" "$mask" > "$OUT/normalized/expected-$2"
  normalize "$3" "$mask" > "$OUT/normalized/$1-$2"
  if diff -q "$OUT/normalized/expected-$2" "$OUT/normalized/$1-$2" > /dev/null; then
    echo "  MATCH    $1 $2 ($(wc -l < "$OUT/normalized/$1-$2" | tr -d " ") records)"
  else
    echo "  DIFFERS  $1 $2"
    diff "$OUT/normalized/expected-$2" "$OUT/normalized/$1-$2" | head -20 || true
    FAILED=1
  fi
}

expect_rc() { # expect_rc <label> <actual rc>
  if [ "$2" -ne 0 ]; then
    echo "  FAIL     $1 return code $2 (expected 0)"
    FAILED=1
  fi
}

echo "== Input: $INPUT"
echo "== COBOL (GnuCOBOL) =="
set +e
INPUT_DIR="$INPUT" WORK_DIR="$OUT/cobol" "$ROOT/run-cbact04c.sh" > "$OUT/cobol.log" 2>&1
COBOL_RC=$?
set -e
expect_rc COBOL "$COBOL_RC"
check cobol sysout.txt   "$OUT/cobol/sysout.txt"
check cobol ACCTDATA.txt "$OUT/cobol/data/ACCTDATA.after.txt"
check cobol SYSTRAN.txt  "$OUT/cobol/SYSTRAN.txt"

echo "== Java =="
JAR="$ROOT/java/target/cbtrn02c.jar"
[ -f "$JAR" ] || (cd "$ROOT/java" && mvn -q -B package -DskipTests)
set +e
java -cp "$JAR" com.carddemo.posttran.IntcalcMain --input-dir "$INPUT" --output-dir "$OUT/java" \
  > "$OUT/java/sysout.txt" 2> "$OUT/java.err"
JAVA_RC=$?
set -e
expect_rc Java "$JAVA_RC"
check java sysout.txt   "$OUT/java/sysout.txt"
check java ACCTDATA.txt "$OUT/java/ACCTDATA.txt"
check java SYSTRAN.txt  "$OUT/java/SYSTRAN.txt"

if [ "$FAILED" -ne 0 ]; then
  echo "RESULT: outputs differ from the baseline (details above; logs in $OUT)"
  exit 1
fi
echo "RESULT: COBOL and Java both match $GOLDEN ($(wc -l < "$OUT/normalized/expected-SYSTRAN.txt" | tr -d ' ') interest transactions)"
