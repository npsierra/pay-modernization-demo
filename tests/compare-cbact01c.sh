#!/usr/bin/env bash
# Runs CBACT01C (account file extract) two ways -- the original COBOL under GnuCOBOL and the
# Java port -- and checks that both match a saved baseline of the COBOL results.
# Usage: compare-cbact01c.sh [INPUT_DIR GOLDEN_DIR [EXPECTED_RC]]
#   default: app/data/ASCII against tests/golden/cbact01c, return code 0
# OUTFILE, ARRYFILE and VBRCFILE are binary (COMP-3 fields, record-length prefixes) and must
# match byte for byte; a file missing from the baseline must not be created. SYSOUT ignores
# trailing spaces. Exits non-zero on any difference.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INPUT="$(cd "${1:-$ROOT/app/data/ASCII}" && pwd)"
GOLDEN="$(cd "${2:-$ROOT/tests/golden/cbact01c}" && pwd)"
EXPECTED_RC="${3:-0}"
OUT="$ROOT/build/cbact01c-compare/$(basename "$GOLDEN")"
rel() { case "$1" in "$ROOT"/*) printf '%s\n' "${1#"$ROOT"/}";; *) printf '%s\n' "$1";; esac; } # show paths relative to the repo
rm -rf "$OUT"
mkdir -p "$OUT/cobol" "$OUT/java" "$OUT/normalized"

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

check_binary() { # check_binary <label> <directory with the actual .dat files>
  local name
  for name in OUTFILE.dat ARRYFILE.dat VBRCFILE.dat; do
    if [ ! -f "$GOLDEN/$name" ]; then
      if [ -e "$2/$name" ]; then
        echo "  DIFFERS  $1 $name exists, but the baseline has none"
        FAILED=1
      else
        echo "  MATCH    $1 $name not created"
      fi
    elif [ -f "$2/$name" ] && cmp -s "$GOLDEN/$name" "$2/$name"; then
      echo "  MATCH    $1 $name ($(wc -c < "$2/$name" | tr -d " ") bytes, identical)"
    else
      echo "  DIFFERS  $1 $name"
      FAILED=1
    fi
  done
  if [ "$FAILED" -ne 0 ] && command -v python3 > /dev/null; then
    python3 "$ROOT/tests/cbact01c_view.py" "$GOLDEN" > "$OUT/normalized/expected-view.txt"
    python3 "$ROOT/tests/cbact01c_view.py" "$2" > "$OUT/normalized/$1-view.txt"
    diff "$OUT/normalized/expected-view.txt" "$OUT/normalized/$1-view.txt" | head -20 || true
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
INPUT_DIR="$INPUT" WORK_DIR="$OUT/cobol" "$ROOT/run-cbact01c.sh" > "$OUT/cobol.log" 2>&1
COBOL_RC=$?
set -e
expect_rc COBOL "$COBOL_RC"
check_sysout cobol "$OUT/cobol/sysout.txt"
check_binary cobol "$OUT/cobol/data"

echo "== Java =="
JAR="$ROOT/java/target/cbtrn02c.jar"
[ -f "$JAR" ] || (cd "$ROOT/java" && mvn -q -B package -DskipTests)
set +e
java -cp "$JAR" com.carddemo.posttran.Cbact01cMain --input-dir "$INPUT" --output-dir "$OUT/java" \
  > "$OUT/java-sysout.txt" 2> "$OUT/java.err"
JAVA_RC=$?
set -e
expect_rc Java "$JAVA_RC"
check_sysout java "$OUT/java-sysout.txt"
check_binary java "$OUT/java"

if [ "$FAILED" -ne 0 ]; then
  echo "RESULT: outputs differ from the baseline (details above; logs in $(rel "$OUT"))"
  exit 1
fi
echo "RESULT: COBOL and Java both match $(rel "$GOLDEN") (return code $EXPECTED_RC)"
