#!/usr/bin/env bash
# Runs CBTRN03C (transaction detail report) two ways -- the original COBOL under GnuCOBOL
# and the Java port -- and checks that both match a saved baseline of the COBOL results.
# Usage: compare-cbtrn03c.sh [INPUT_DIR GOLDEN_DIR [EXPECTED_RC]]
#   default: CBTRN02C's saved posted transactions with the sample lookups (the input
#   run-cbtrn03c.sh builds without INPUT_DIR) against tests/golden/cbtrn03c, return code 0.
# The report has no run-time timestamps: only trailing spaces are ignored. A report missing
# from the baseline (abend before TRANREPT is opened) must not be created.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GOLDEN="$(cd "${2:-$ROOT/tests/golden/cbtrn03c}" && pwd)"
EXPECTED_RC="${3:-0}"
OUT="$ROOT/build/cbtrn03c-compare/$(basename "$GOLDEN")"
rm -rf "$OUT"
mkdir -p "$OUT/cobol" "$OUT/java" "$OUT/normalized"
if [ -n "${1:-}" ]; then
  INPUT="$(cd "$1" && pwd)"
else
  INPUT="$OUT/input"
  mkdir -p "$INPUT"
  cp "$ROOT/tests/golden/cbtrn02c/TRANSACT.txt" "$INPUT/transact.txt"
  cp "$ROOT/app/data/ASCII/cardxref.txt" "$ROOT/app/data/ASCII/trantype.txt" \
     "$ROOT/app/data/ASCII/trancatg.txt" "$INPUT/"
  echo "2026-10-27 2026-10-27" > "$INPUT/dateparm.txt"
fi

normalize() { awk '{ sub(/\r$/, ""); sub(/ +$/, ""); print }' "$1"; }

FAILED=0
check() { # check <label> <baseline name> <actual file>
  if [ ! -f "$GOLDEN/$2" ]; then
    if [ -e "$3" ]; then
      echo "  DIFFERS  $1 $2 exists, but the baseline has none"
      FAILED=1
    else
      echo "  MATCH    $1 $2 not created"
    fi
    return
  fi
  normalize "$GOLDEN/$2" > "$OUT/normalized/expected-$2"
  if [ -f "$3" ] && normalize "$3" > "$OUT/normalized/$1-$2" \
      && diff -q "$OUT/normalized/expected-$2" "$OUT/normalized/$1-$2" > /dev/null; then
    echo "  MATCH    $1 $2 ($(wc -l < "$OUT/normalized/$1-$2" | tr -d " ") lines)"
  else
    echo "  DIFFERS  $1 $2"
    [ -f "$3" ] && diff "$OUT/normalized/expected-$2" "$OUT/normalized/$1-$2" | head -20 || true
    FAILED=1
  fi
}

expect_rc() { # expect_rc <label> <actual rc>
  if [ "$2" -ne "$EXPECTED_RC" ]; then
    echo "  FAIL     $1 return code $2 (expected $EXPECTED_RC)"
    FAILED=1
  fi
}

echo "== Input: $INPUT"
echo "== COBOL (GnuCOBOL) =="
set +e
INPUT_DIR="$INPUT" WORK_DIR="$OUT/cobol" "$ROOT/run-cbtrn03c.sh" > "$OUT/cobol.log" 2>&1
COBOL_RC=$?
set -e
expect_rc COBOL "$COBOL_RC"
check cobol sysout.txt   "$OUT/cobol/sysout.txt"
check cobol TRANREPT.txt "$OUT/cobol/TRANREPT.txt"

echo "== Java =="
JAR="$ROOT/java/target/cbtrn02c.jar"
[ -f "$JAR" ] || (cd "$ROOT/java" && mvn -q -B package -DskipTests)
set +e
java -cp "$JAR" com.carddemo.posttran.Cbtrn03cMain --input-dir "$INPUT" --output-dir "$OUT/java" \
  > "$OUT/java-sysout.txt" 2> "$OUT/java.err"
JAVA_RC=$?
set -e
expect_rc Java "$JAVA_RC"
check java sysout.txt   "$OUT/java-sysout.txt"
check java TRANREPT.txt "$OUT/java/TRANREPT.txt"

if [ "$FAILED" -ne 0 ]; then
  echo "RESULT: outputs differ from the baseline (details above; logs in $OUT)"
  exit 1
fi
echo "RESULT: COBOL and Java both match $GOLDEN (return code $EXPECTED_RC)"
