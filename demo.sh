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

step() { # step <title> [line ...] -- one short line each, no wrapping
  if [ -z "${DEMO_NO_PAUSE:-}" ] && [ -t 0 ]; then
    printf '\n[Enter] %s ' "$1"; read -r _
  fi
  printf '%s==================================================================\n %s\n==================================================================%s\n' "$CYAN$BOLD" "$1" "$RESET"
  shift
  say "$@"
}

say() { # say <sentence ...>: one sentence per line, a blank line before and after
  echo
  for line in "$@"; do if [ -n "$line" ]; then printf ' %s\n' "$line"; else echo; fi; done
  echo
}

spin() { # spin <label> <log file> <command ...>: run the command, spinner while it runs
  local label="$1" log="$2"; shift 2
  if [ -t 1 ]; then
    local frames=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏) i=0 rc=0 pid
    "$@" > "$log" 2>&1 & pid=$!
    while kill -0 "$pid" 2>/dev/null || [ "$i" -lt 12 ]; do
      printf '\r %s%s%s %s' "$CYAN" "${frames[i % 10]}" "$RESET" "$label"; i=$((i + 1)); sleep 0.1
    done
    wait "$pid" || rc=$?
    printf '\r %s%s%s %s\n' "$GREEN" "done" "$RESET" "$label"
    return "$rc"
  fi
  printf ' %s\n' "$label"
  "$@" > "$log" 2>&1
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
  local rc=0
  spin "Running COBOL CBTRN02C   in: app/data/ASCII   out: build/demo/${work##*/}" "$work.log" \
    env "$@" WORK_DIR="$work" "$ROOT/run-cbtrn02c.sh" || rc=$?
  if [ "$rc" -ne 0 ] && [ "$rc" -ne 4 ]; then echo "run failed (rc $rc), see $work.log:"; tail -3 "$work.log"; exit "$rc"; fi
  echo " Return code: $rc"
}

build() { (cd "$1" && mvn -q -B package -DskipTests); }
if [ ! -f "$JAR" ]; then
  spin "Building the Java port (first run only)" "$OUT/build.log" build "$ROOT/java"
fi

# Step 4's broken copy: same Java with one rule changed -- a transaction that lands exactly
# on the credit limit is rejected instead of posted (< becomes <=). The real code is untouched.
rm -rf "$BROKEN" && mkdir -p "$BROKEN"
cp -R "$ROOT/java/pom.xml" "$ROOT/java/src" "$BROKEN/"
RULE="$BROKEN/src/main/java/com/carddemo/posttran/TransactionValidator.java"
sed -i.orig 's/creditLimit().compareTo(projected) < 0/creditLimit().compareTo(projected) <= 0/' "$RULE"
if cmp -s "$RULE" "$RULE.orig"; then echo "could not apply the step 4 rule change"; exit 1; fi
spin "Preparing step 4" "$OUT/build-broken.log" build "$BROKEN"

step "1. Original COBOL, unchanged, run here with GnuCOBOL" \
  "Program: app/cbl/CBTRN02C.cbl, not modified." \
  "Input: 300 daily transactions." \
  "Watch: how many post, how many are rejected, and why."
run "$OUT/cobol" SIGN_ENCODING=EBCDIC
counts "$OUT/cobol/sysout.txt" "$OUT/cobol/rejects.txt"
say "One reject reason: the sample data trips one of the four rules." \
    "" \
    "Amounts carry their sign in the last character, the mainframe way:"
printf '  %-13s %-10s %s\n' "In the file" "Means" ""
for pat in '[}J-R]$' '[{A-I]$'; do
  a="$(cut -c133-143 "$ROOT/app/data/ASCII/dailytran.txt" | grep -m1 -E "$pat")"
  printf '  %-13s %-10s (%s)\n' "$a" "$(echo "$a" | signed)" "$(echo "$a" | explain_sign)"
done
say "Read them the wrong way: 299 of 300 rejected instead of 38."

step "2. Java port, same input" \
  "Program: the Java 17 port in java/." \
  "Watch: the same counts, the same rejects."
JAVA_RC=0
spin "Running Java CBTRN02C    in: app/data/ASCII   out: build/demo/java" "$OUT/java.sysout.txt" \
  java -jar "$JAR" --input-dir "$ROOT/app/data/ASCII" --output-dir "$OUT/java" || JAVA_RC=$?
echo " Return code: $JAVA_RC"
if [ "$JAVA_RC" -ne 4 ]; then echo "Java run failed (expected rc 4), see $OUT/java.sysout.txt"; exit 1; fi
counts "$OUT/java.sysout.txt" "$OUT/java/DALYREJS.txt"
echo

step "3. Prove it: COBOL and Java vs the saved baseline, file by file" \
  "Baseline: a saved copy of the COBOL output." \
  "MATCH = identical, byte for byte." \
  "" \
  "sysout    program log" \
  "TRANSACT  posted transactions" \
  "ACCTDATA  account balances" \
  "TCATBALF  balances per transaction category" \
  "DALYREJS  rejected transactions, with the reason"
COMPARE_RC=0
spin "Running COBOL and Java, comparing 5 files each with tests/golden/cbtrn02c" "$OUT/compare.log" \
  "$ROOT/tests/compare-cbtrn02c.sh" || COMPARE_RC=$?
echo
paint < "$OUT/compare.log"
echo
if [ "$COMPARE_RC" -ne 0 ]; then echo " Comparison failed (rc $COMPARE_RC), see build/demo/compare.log"; exit "$COMPARE_RC"; fi

step "4. Break one business rule in the Java. Does the check catch it?" \
  "A throwaway copy of the Java gets one rule wrong:" \
  "a purchase landing exactly on the credit limit is rejected instead of posted." \
  "The real Java is untouched."
printf ' The change (< becomes <=):\n'
diff "$RULE.orig" "$RULE" | sed "s/^</  ${GREEN}</; s/^>/  ${RED}>/; s/\$/${RESET}/; /^[0-9]/d" || true
EDGE="$ROOT/tests/data/cbtrn02c-edge/dailytran.txt"
say "First, the 300 sample transactions."
SAMPLE_RC=0
CBTRN02C_JAR="$BROKEN/target/cbtrn02c.jar" \
  spin "Running the broken Java   in: app/data/ASCII   vs: tests/golden/cbtrn02c" "$OUT/broken-sample.log" \
  "$ROOT/tests/compare-cbtrn02c.sh" || SAMPLE_RC=$?
grep -E '^RESULT' "$OUT/broken-sample.log" | sed 's/ (details above.*//' | paint
if [ "$SAMPLE_RC" -eq 0 ]; then
  echo
  printf ' %sSample data alone: MISSED it.%s\n' "$YELLOW$BOLD" "$RESET"
  say "No sample transaction lands exactly on a limit."
else
  say "Sample data: caught it already."
fi
EDGE_REJ="$ROOT/tests/golden/cbtrn02c-edge/DALYREJS.txt"
EDGE_N="$(wc -l < "$EDGE" | tr -d ' ')"; EDGE_R="$(wc -l < "$EDGE_REJ" | tr -d ' ')"
reasons="$(cut -c351-354 "$EDGE_REJ" | sort | uniq -c | awk '
  { name[ "0100" ] = "unknown card"; name[ "0101" ] = "missing account"; name[ "0102" ] = "over limit"; name[ "0103" ] = "expired"
    printf "   %d %s (%s)\n", $1, name[$2], substr($2, 2) }')"
printf ' Then the %s hand-written edge cases (tests/data/cbtrn02c-edge):\n' "$EDGE_N"
printf '  %s posted\n  %s rejected:\n%s\n' "$((EDGE_N - EDGE_R))" "$EDGE_R" "$reasons"
printf ' Including: %s901 posted exactly at limit%s  <- the one that matters\n' "$YELLOW$BOLD" "$RESET"
echo
BROKEN_RC=0
CBTRN02C_JAR="$BROKEN/target/cbtrn02c.jar" \
  spin "Running COBOL and the broken Java   in: tests/data/cbtrn02c-edge" "$OUT/broken-compare.log" \
  "$ROOT/tests/compare-cbtrn02c.sh" "$ROOT/tests/data/cbtrn02c-edge" "$ROOT/tests/golden/cbtrn02c-edge" || BROKEN_RC=$?
echo
awk '/^== COBOL/ { c = 1; next }
     /^== Java/  { c = 0; printf "  %s    COBOL: %d of %d files match the baseline\n", (m == t) ? "MATCH" : "DIFFERS", m, t; next }
     c && /^  (MATCH|DIFFERS) / { t++; if ($1 == "MATCH") m++; next }
     /^  (MATCH|DIFFERS) |^RESULT/' "$OUT/broken-compare.log" | sed 's/ (details above.*//' | paint
if [ "$BROKEN_RC" -eq 0 ]; then echo " Not caught -- the comparison should have failed"; exit 1; fi
echo
printf ' %sEdge cases: CAUGHT it.%s\n' "$GREEN$BOLD" "$RESET"
NORM="$ROOT/build/cbtrn02c-compare/cbtrn02c-edge/normalized"
say "Rejected by the broken Java, posted by the COBOL:"
{ diff "$NORM/expected-DALYREJS.txt" "$NORM/java-DALYREJS.txt" || true; } | grep '^>' | while IFS= read -r line; do
  rec="${line#> }"
  printf '  %sid %s  amount %s  "%s"\n  -> %s%s\n' "$YELLOW$BOLD" "${rec:0:16}" "$(echo "${rec:132:11}" | signed)" \
    "$(echo "${rec:32:100}" | sed 's/ *$//')" "$(echo "${rec:350:80}" | sed 's/ *$//')" "$RESET"
done
rm -rf "$BROKEN"
say "400.00 of credit left, a 400.00 purchase." \
    "COBOL posts it. The broken Java rejects it." \
    "One character. Invisible on the sample data. Caught here." \
    "" \
    "Throwaway copy deleted. The real Java was never changed." \
    "" \
    "This check runs on every pull request, for all three programs ported so far:" \
    "" \
    " - CBTRN02C  posts transactions" \
    " - CBACT04C  calculates interest" \
    " - CBACT01C  extracts accounts" \
    "" \
    "Sample data, hand-written edge cases, 200 generated batches each." \
    "COBOL and Java have to agree on every file."
printf ' %sEvery change must now prove it matches the original before approval.%s\n\n' "$BOLD" "$RESET"
