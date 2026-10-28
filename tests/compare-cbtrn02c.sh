#!/usr/bin/env bash
# Runs CBTRN02C two ways on one input set -- the original COBOL under GnuCOBOL and the
# Java port -- and checks that both match a saved baseline of the COBOL results.
# Usage: compare-cbtrn02c.sh [INPUT_DIR GOLDEN_DIR]   (CBTRN02C_JAR=<jar> tests a different Java build)
#   default: app/data/ASCII against tests/golden/cbtrn02c (the repo's sample data)
# Trailing spaces and the run-time processing timestamp (TRANSACT columns 305-330) are
# ignored; everything else must be identical. Exits non-zero on any difference.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INPUT="$(cd "${1:-$ROOT/app/data/ASCII}" && pwd)"
GOLDEN="$(cd "${2:-$ROOT/tests/golden/cbtrn02c}" && pwd)"
OUT="$ROOT/build/cbtrn02c-compare/$(basename "$GOLDEN")"
COBOL_WORK="$OUT/cobol"
JAVA_OUT="$OUT/java"
rm -rf "$OUT"
mkdir -p "$COBOL_WORK" "$JAVA_OUT" "$OUT/normalized"

normalize() { # normalize <file> <mask-processing-timestamp: yes|no>
  awk -v mask="$2" '{
    sub(/\r$/, "")
    if (mask == "yes" && length($0) > 304) {
      $0 = sprintf("%-350s", $0)
      $0 = substr($0, 1, 304) sprintf("%26s", "") substr($0, 331)
    }
    sub(/ +$/, "")
    print
  }' "$1"
}

FAILED=0
check() { # check <label> <baseline name> <actual file>
  local mask=no
  [ "$2" = TRANSACT.txt ] && mask=yes
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
  if [ "$2" -ne 4 ]; then
    echo "  FAIL     $1 return code $2 (expected 4: completed with rejects)"
    FAILED=1
  fi
}

echo "== Input: $INPUT"
echo "== COBOL (GnuCOBOL) =="
set +e
INPUT_DIR="$INPUT" WORK_DIR="$COBOL_WORK" "$ROOT/run-cbtrn02c.sh" > "$OUT/cobol.log" 2>&1
COBOL_RC=$?
set -e
expect_rc COBOL "$COBOL_RC"
check cobol sysout.txt   "$COBOL_WORK/sysout.txt"
check cobol TRANSACT.txt "$COBOL_WORK/data/TRANSACT.after.txt"
check cobol ACCTDATA.txt "$COBOL_WORK/data/ACCTDATA.after.txt"
check cobol TCATBALF.txt "$COBOL_WORK/data/TCATBALF.after.txt"
check cobol DALYREJS.txt "$COBOL_WORK/rejects.txt"

echo "== Java =="
JAR="${CBTRN02C_JAR:-$ROOT/java/target/cbtrn02c.jar}"
[ -f "$JAR" ] || (cd "$ROOT/java" && mvn -q -B package -DskipTests)
set +e
java -jar "$JAR" --input-dir "$INPUT" --output-dir "$JAVA_OUT" > "$JAVA_OUT/sysout.txt" 2> "$OUT/java.err"
JAVA_RC=$?
set -e
expect_rc Java "$JAVA_RC"
check java sysout.txt   "$JAVA_OUT/sysout.txt"
check java TRANSACT.txt "$JAVA_OUT/TRANSACT.txt"
check java ACCTDATA.txt "$JAVA_OUT/ACCTDATA.txt"
check java TCATBALF.txt "$JAVA_OUT/TCATBALF.txt"
check java DALYREJS.txt "$JAVA_OUT/DALYREJS.txt"

if [ "$FAILED" -ne 0 ]; then
  echo "RESULT: outputs differ from the baseline (details above; logs in $OUT)"
  exit 1
fi
echo "RESULT: COBOL and Java both match $GOLDEN ($(grep -m1 'TRANSACTIONS PROCESSED' "$GOLDEN/sysout.txt" | tr -s ' '), $(wc -l < "$OUT/normalized/expected-DALYREJS.txt" | tr -d ' ') rejected)"
