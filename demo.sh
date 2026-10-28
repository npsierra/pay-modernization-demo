#!/usr/bin/env bash
# Live demo of the CBTRN02C modernization, one step at a time:
#   1. original COBOL under GnuCOBOL   2. the sign bug (mainframe signs read the ASCII way)
#   3. the Java port                   4. COBOL vs Java vs saved baseline, file by file
# Press Enter between steps; set DEMO_NO_PAUSE=1 to run straight through.
set -euo pipefail

# The demo always uses the bundled sample data and sets each run's options itself.
unset INPUT_DIR WORK_DIR SIGN_ENCODING

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build/demo"
JAR="$ROOT/java/target/cbtrn02c.jar"
mkdir -p "$OUT"

step() {
  if [ -z "${DEMO_NO_PAUSE:-}" ] && [ -t 0 ]; then
    printf '\n[Enter] %s ' "$1"; read -r _
  fi
  printf '\n==================================================================\n %s\n==================================================================\n' "$1"
}

counts() { # counts <work dir>
  grep -E 'TRANSACTIONS (PROCESSED|REJECTED)' "$1/sysout.txt" | tr -s ' '
  echo " Rejects by reason:"
  cut -c351-430 "$1/rejects.txt" | sed 's/ *$//' | sort | uniq -c | sed 's/^/  /'
}

run() { # run <work dir> [VAR=value ...] -- runs CBTRN02C, keeps the log, tolerates rc 4
  local work="$1"; shift
  set +e
  env "$@" WORK_DIR="$work" "$ROOT/run-cbtrn02c.sh" > "$work.log" 2>&1
  local rc=$?
  set -e
  if [ "$rc" -gt 4 ]; then echo "run failed (rc $rc), see $work.log"; exit "$rc"; fi
  echo " Return code: $rc"
}

if [ ! -f "$JAR" ]; then
  echo "Building the Java port (first run only)..."
  (cd "$ROOT/java" && mvn -q -B package -DskipTests)
fi

step "1. Original COBOL, unchanged, under GnuCOBOL (cobc -fsign=EBCDIC)"
run "$OUT/cobol" SIGN_ENCODING=EBCDIC
counts "$OUT/cobol"

step "2. The sign bug: same program and data, signs read the ASCII way"
run "$OUT/sign-bug" SIGN_ENCODING=ASCII
counts "$OUT/sign-bug"
echo " Sample amounts as stored (last character carries the sign):"
cut -c133-143 "$ROOT/app/data/ASCII/dailytran.txt" | grep -m3 -E '[{}A-R]$' | awk '{
  c = substr($0, 11, 1); i = index("{ABCDEFGHI}JKLMNOPQR", c) - 1
  d = i % 10; sign = (i >= 10) ? "-" : "+"
  v = substr($0, 1, 10) d
  printf "  %s  mainframe meaning: %s%d.%s\n", $0, sign, substr(v, 1, 9) + 0, substr(v, 10, 2)
}'

step "3. Java port on the same input"
set +e
java -jar "$JAR" --input-dir "$ROOT/app/data/ASCII" --output-dir "$OUT/java" > "$OUT/java.sysout.txt" 2>&1
JAVA_RC=$?
set -e
echo " Return code: $JAVA_RC"
if [ "$JAVA_RC" -ne 4 ]; then echo "Java run failed (expected rc 4), see $OUT/java.sysout.txt"; exit 1; fi
grep -E 'TRANSACTIONS (PROCESSED|REJECTED)' "$OUT/java.sysout.txt" | tr -s ' '

step "4. Prove it: COBOL and Java vs the saved COBOL baseline, file by file"
"$ROOT/tests/compare-cbtrn02c.sh"
