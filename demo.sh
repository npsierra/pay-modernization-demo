#!/usr/bin/env bash
# Live demo of the CBTRN02C modernization, one step at a time:
#   1. original COBOL under GnuCOBOL   2. the Java port
#   3. COBOL vs Java vs saved baseline, file by file
#   4. break one business rule in a throwaway copy of the Java and watch the comparison catch it
# Press Enter between steps; set DEMO_NO_PAUSE=1 to run straight through.
set -euo pipefail

# The demo always uses the bundled sample data and sets each run's options itself.
unset INPUT_DIR WORK_DIR SIGN_ENCODING

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build/demo"
JAR="$ROOT/java/target/cbtrn02c.jar"
BROKEN="$OUT/java-broken"
unset CBTRN02C_JAR
mkdir -p "$OUT"

# Colour only when printing to a terminal (never in CI or when piped to a file).
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  BOLD=$'\e[1m' GREEN=$'\e[32m' RED=$'\e[31m' YELLOW=$'\e[33m' CYAN=$'\e[36m' RESET=$'\e[0m'
else
  BOLD= GREEN= RED= YELLOW= CYAN= RESET=
fi

step() { # step <title> <what to look at>
  if [ -z "${DEMO_NO_PAUSE:-}" ] && [ -t 0 ]; then
    printf '\n[Enter] %s ' "$1"; read -r _
  fi
  printf '\n%s==================================================================\n %s\n==================================================================%s\n' "$CYAN$BOLD" "$1" "$RESET"
  printf ' %s\n\n' "$2"
}

paint() { # colour the comparison script's verdicts
  sed -e "s/^  MATCH /  ${GREEN}MATCH${RESET} /" \
      -e "s/^  DIFFERS /  ${RED}DIFFERS${RESET} /" \
      -e "s/^  FAIL /  ${RED}FAIL${RESET} /" \
      -e "s/^RESULT: COBOL and Java both match/${GREEN}${BOLD}RESULT: COBOL and Java both match${RESET}/" \
      -e "s/^RESULT: outputs differ/${RED}${BOLD}RESULT: outputs differ${RESET}/"
}

signed() { # 11-char zoned amount, sign in the last character (mainframe overpunch) -> -919.00
  awk '{
    c = substr($0, 11, 1); i = index("{ABCDEFGHI}JKLMNOPQR", c) - 1
    d = i % 10; sign = (i >= 10) ? "-" : ""
    v = substr($0, 1, 10) d
    printf "%s%d.%s\n", sign, substr(v, 1, 9) + 0, substr(v, 10, 2)
  }'
}

counts() { # counts <sysout file> <rejects file>
  grep -E 'TRANSACTIONS (PROCESSED|REJECTED)' "$1" | tr -s ' '
  echo " Rejects by reason:"
  cut -c351-430 "$2" | sed 's/ *$//' | sort | uniq -c | sed 's/^/  /'
}

explain_sign() { # explain_sign <11-char amount> -> "} = 0 with a minus sign folded in"
  awk '{ c = substr($0, 11, 1); i = index("{ABCDEFGHI}JKLMNOPQR", c) - 1
         printf "%s = %d, %s\n", c, i % 10, (i >= 10) ? "with a minus sign folded in" : "positive" }'
}

run() { # run <work dir> [VAR=value ...] -- runs CBTRN02C, keeps the log, accepts rc 0 or 4 (some rejected)
  local work="$1"; shift
  set +e
  env "$@" WORK_DIR="$work" "$ROOT/run-cbtrn02c.sh" > "$work.log" 2>&1
  local rc=$?
  set -e
  if [ "$rc" -ne 0 ] && [ "$rc" -ne 4 ]; then echo "run failed (rc $rc), see $work.log:"; tail -3 "$work.log"; exit "$rc"; fi
  echo " Return code: $rc"
}

if [ ! -f "$JAR" ]; then
  echo "Building the Java port (first run only)..."
  (cd "$ROOT/java" && mvn -q -B package -DskipTests)
fi

# Step 4's broken copy: same Java with one rule changed -- a transaction that lands exactly
# on the credit limit is rejected instead of posted (< becomes <=). The real code is untouched.
echo "Preparing step 4 (a throwaway copy of the Java with one rule changed)..."
rm -rf "$BROKEN" && mkdir -p "$BROKEN"
cp -R "$ROOT/java/pom.xml" "$ROOT/java/src" "$BROKEN/"
RULE="$BROKEN/src/main/java/com/carddemo/posttran/TransactionValidator.java"
sed -i.orig 's/creditLimit().compareTo(projected) < 0/creditLimit().compareTo(projected) <= 0/' "$RULE"
if cmp -s "$RULE" "$RULE.orig"; then echo "could not apply the step 4 rule change"; exit 1; fi
(cd "$BROKEN" && mvn -q -B package -DskipTests)

step "1. Original COBOL, unchanged, compiled and run here with GnuCOBOL" \
  "The CardDemo program app/cbl/CBTRN02C.cbl, not modified. Look at: how many of the 300 daily transactions post, how many are rejected, and why."
run "$OUT/cobol" SIGN_ENCODING=EBCDIC
counts "$OUT/cobol/sysout.txt" "$OUT/cobol/rejects.txt"
echo
echo " Amounts in the input file have a letter or bracket as their last character: the mainframe folds the +/- sign"
echo " into the last digit. GnuCOBOL is told to read them the mainframe way (-fsign=EBCDIC)."
printf '  %-13s %-10s %s\n' "In the file" "Means" ""
for pat in '[}J-R]$' '[{A-I]$'; do
  a="$(cut -c133-143 "$ROOT/app/data/ASCII/dailytran.txt" | grep -m1 -E "$pat")"
  printf '  %-13s %-10s (%s)\n' "$a" "$(echo "$a" | signed)" "$(echo "$a" | explain_sign)"
done
echo " Read these the wrong way and every amount flips sign: 299 of the 300 transactions are rejected instead of 38."

step "2. Java port on the same input" \
  "The Java 17 port (java/) reads the same input files. Look at: the same counts and the same rejects by reason."
set +e
java -jar "$JAR" --input-dir "$ROOT/app/data/ASCII" --output-dir "$OUT/java" > "$OUT/java.sysout.txt" 2>&1
JAVA_RC=$?
set -e
echo " Return code: $JAVA_RC"
if [ "$JAVA_RC" -ne 4 ]; then echo "Java run failed (expected rc 4), see $OUT/java.sysout.txt"; exit 1; fi
counts "$OUT/java.sysout.txt" "$OUT/java/DALYREJS.txt"

step "3. Prove it: COBOL and Java vs the saved COBOL baseline, file by file" \
  "The automatic check. Every file both runs produce is compared with a saved copy of the COBOL output. MATCH = identical."
echo " The five files:  sysout = program log   TRANSACT = posted transactions   ACCTDATA = account balances"
echo "                  TCATBALF = balances per transaction category   DALYREJS = rejected transactions, with the reason"
echo
"$ROOT/tests/compare-cbtrn02c.sh" | paint

step "4. Break one business rule in the Java: is the comparison able to catch it?" \
  "What if the Java got one rule slightly wrong? A throwaway copy is changed so a purchase landing exactly on the credit limit is rejected instead of posted. The real Java is untouched. DIFFERS = the check caught it."
echo " The change (< becomes <=):"
diff "$RULE.orig" "$RULE" | sed "s/^</  ${GREEN}</; s/^>/  ${RED}>/; s/\$/${RESET}/; /^[0-9]/d" || true
echo
EDGE="$ROOT/tests/data/cbtrn02c-edge/dailytran.txt"
echo " The hand-made edge cases (tests/data/cbtrn02c-edge), one transaction each. None of the 300 sample"
echo " transactions lands exactly on a credit limit, so the sample data alone would not catch this."
echo " (First number: test account. 100/101/102/103: the reject reason the COBOL gives it.)"
cut -c33-132 "$EDGE" | sed "s/ *\$//; s/^/  - /; s/^\(  - 901 posted exactly at limit\)/${YELLOW}\1${RESET}/"
echo
echo " Comparing the broken Java on those $(wc -l < "$EDGE" | tr -d ' ') transactions:"
set +e
CBTRN02C_JAR="$BROKEN/target/cbtrn02c.jar" "$ROOT/tests/compare-cbtrn02c.sh" \
  "$ROOT/tests/data/cbtrn02c-edge" "$ROOT/tests/golden/cbtrn02c-edge" > "$OUT/broken-compare.log" 2>&1
BROKEN_RC=$?
set -e
grep -E '^  (MATCH|DIFFERS)|^RESULT' "$OUT/broken-compare.log" | sed 's/ (details above.*//' | paint
if [ "$BROKEN_RC" -eq 0 ]; then echo " Not caught -- the comparison should have failed"; exit 1; fi
NORM="$ROOT/build/cbtrn02c-compare/cbtrn02c-edge/normalized"
echo
echo " The transaction the broken Java rejected but the original COBOL posted:"
{ diff "$NORM/expected-DALYREJS.txt" "$NORM/java-DALYREJS.txt" || true; } | grep '^>' | while IFS= read -r line; do
  rec="${line#> }"
  printf '  %sid %s  amount %s  "%s"\n  -> %s%s\n' "$YELLOW$BOLD" "${rec:0:16}" "$(echo "${rec:132:11}" | signed)" \
    "$(echo "${rec:32:100}" | sed 's/ *$//')" "$(echo "${rec:350:80}" | sed 's/ *$//')" "$RESET"
done
rm -rf "$BROKEN"
echo
echo " In words: the account had exactly 400.00 of credit left and a 400.00 purchase came in. The original COBOL"
echo " posts it; the broken Java rejects it. One character of difference, invisible on the sample data, caught here."
echo
echo " The throwaway copy is deleted; the real Java was never changed."
printf '\n%s This same check runs on every pull request, for all three programs ported so far:\n CBTRN02C (posts transactions), CBACT04C (calculates interest), CBACT01C (extracts accounts).\n Sample data, hand-made edge cases and 200 generated batches each. COBOL and Java have to agree on every file.%s\n' "$BOLD" "$RESET"
