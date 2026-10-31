#!/usr/bin/env bash
# Runs CBTRN01C (daily transaction verification) two ways -- the original COBOL under GnuCOBOL
# and the Java port -- and checks that both match a saved baseline of the COBOL results.
# Usage: compare-cbtrn01c.sh [INPUT_DIR GOLDEN_DIR [EXPECTED_RC]]
#   default: app/data/ASCII against tests/golden/cbtrn01c, return code 0
# The program writes no files: SYSOUT is the only output. Trailing spaces are ignored (there
# are no run-time timestamps to mask). Exits non-zero on any difference.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INPUT="$(cd "${1:-$ROOT/app/data/ASCII}" && pwd)"
GOLDEN="$(cd "${2:-$ROOT/tests/golden/cbtrn01c}" && pwd)"
EXPECTED_RC="${3:-0}"
OUT="$ROOT/build/cbtrn01c-compare/$(basename "$GOLDEN")"
rel() { case "$1" in "$ROOT"/*) printf '%s\n' "${1#"$ROOT"/}";; *) printf '%s\n' "$1";; esac; } # show paths relative to the repo
rm -rf "$OUT"
mkdir -p "$OUT/cobol" "$OUT/java" "$OUT/normalized"
# Same TRANFILE rule as run-cbtrn01c.sh, passed to both versions.
if [ -f "$INPUT/transact.txt" ]; then TRANSACT="$INPUT/transact.txt"; else TRANSACT="$ROOT/tests/golden/cbtrn02c/TRANSACT.txt"; fi

normalize() { awk '{ sub(/\r$/, ""); sub(/ +$/, ""); print }' "$1"; }

FAILED=0
check_sysout() { # check_sysout <label> <actual file>
  normalize "$GOLDEN/sysout.txt" > "$OUT/normalized/expected-sysout.txt"
  normalize "$2" > "$OUT/normalized/$1-sysout.txt"
  if diff -q "$OUT/normalized/expected-sysout.txt" "$OUT/normalized/$1-sysout.txt" > /dev/null; then
    echo "  MATCH    $1 sysout.txt ($(wc -l < "$OUT/normalized/$1-sysout.txt" | tr -d " ") lines)"
  else
    echo "  DIFFERS  $1 sysout.txt"
    diff "$OUT/normalized/expected-sysout.txt" "$OUT/normalized/$1-sysout.txt" | head -20 || true
    FAILED=1
  fi
}

expect_rc() { # expect_rc <label> <actual rc>
  if [ "$2" -ne "$EXPECTED_RC" ]; then
    echo "  FAIL     $1 return code $2 (expected $EXPECTED_RC)"
    FAILED=1
  fi
}

echo "== Input: $(rel "$INPUT")"
echo "== COBOL (GnuCOBOL) =="
set +e
INPUT_DIR="$INPUT" WORK_DIR="$OUT/cobol" TRANSACT_TXT="$TRANSACT" "$ROOT/run-cbtrn01c.sh" > "$OUT/cobol.log" 2>&1
COBOL_RC=$?
set -e
expect_rc COBOL "$COBOL_RC"
check_sysout cobol "$OUT/cobol/sysout.txt"

echo "== Java =="
JAR="$ROOT/java/target/cbtrn02c.jar"
[ -f "$JAR" ] || (cd "$ROOT/java" && mvn -q -B package -DskipTests)
set +e
java -cp "$JAR" com.carddemo.posttran.Cbtrn01cMain --input-dir "$INPUT" --transact-file "$TRANSACT" \
  > "$OUT/java/sysout.txt" 2> "$OUT/java.err"
JAVA_RC=$?
set -e
expect_rc Java "$JAVA_RC"
check_sysout java "$OUT/java/sysout.txt"

if [ "$FAILED" -ne 0 ]; then
  echo "RESULT: outputs differ from the baseline (details above; logs in $(rel "$OUT"))"
  exit 1
fi
N() { grep -c "$1" "$OUT/normalized/expected-sysout.txt" || true; }
echo "RESULT: COBOL and Java both match $(rel "$GOLDEN") (return code $EXPECTED_RC; $(N '^SUCCESSFUL READ OF ACCOUNT FILE') accounts found, $(N 'NOT FOUND$') not found, $(N 'COULD NOT BE VERIFIED') cards not verified)"
