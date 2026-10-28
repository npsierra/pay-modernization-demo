# Running CBTRN02C (daily transaction posting) with GnuCOBOL

This runs the batch program `app/cbl/CBTRN02C.cbl` on Linux with GnuCOBOL. It uses the sample data in `app/data/ASCII` and needs no mainframe.

## Quick start

```bash
gnucobol/install-gnucobol.sh       # Ubuntu: builds GnuCOBOL 3.2 (apt only has 3.1.2)
brew install gnucobol              # macOS: GnuCOBOL 3.2
./run-cbtrn02c.sh
```

Everything (CI, Devin's VM, macOS) uses GnuCOBOL 3.2, the version Homebrew installs, so every count in this file is what 3.2 prints. The script compiles everything and rebuilds the input files from `app/data/ASCII`, so each run starts from the same data. It then runs the program and prints a summary. Set `INPUT_DIR` to run it on other input files with the same names. Everything it creates goes in `gnucobol/work/` (or `WORK_DIR`), which git ignores:

| File | Contents |
|---|---|
| `sysout.txt` | Program output (the job's SYSOUT) |
| `rejects.txt` | Rejected transactions, one per line: 350-byte transaction + 80-byte reason |
| `data/ACCTDATA.before.txt` / `.after.txt` | Account master before and after posting |
| `data/TCATBALF.after.txt` | Category balances after posting |
| `data/TRANSACT.after.txt` | Transaction master (posted transactions) |

The script exits with the program's return code, as the mainframe step would: 0 means everything posted, 4 means it finished with some rejects, and anything higher means it failed.

## How the JCL maps to this setup

`app/jcl/POSTTRAN.jcl` (STEP15) defines the files. Each JCL `DD` statement becomes a `DD_<name>` environment variable, which GnuCOBOL reads to find the file for `ASSIGN TO <name>`.

| DD | Use | Mainframe dataset | Local file | Built from |
|---|---|---|---|---|
| DALYTRAN | input, sequential, 350 bytes | `DALYTRAN.PS` | `DALYTRAN.dat` | `dailytran.txt` with line feeds removed |
| XREFFILE | input, KSDS key 16 | `CARDXREF.VSAM.KSDS` | `CARDXREF.idx` | `cardxref.txt` loaded by `KSDSUTIL` |
| ACCTFILE | read/update, KSDS key 11 | `ACCTDATA.VSAM.KSDS` | `ACCTDATA.idx` | `acctdata.txt` loaded by `KSDSUTIL` |
| TCATBALF | read/update, KSDS key 17 | `TCATBALF.VSAM.KSDS` | `TCATBALF.idx` | `tcatbal.txt` loaded by `KSDSUTIL` |
| TRANFILE | output, KSDS key 16 | `TRANSACT.VSAM.KSDS` | `TRANSACT.idx` | created by the program (`OPEN OUTPUT`) |
| DALYREJS | output, sequential, 430 bytes | `DALYREJS(+1)` (GDG) | `DALYREJS.dat` | created by the program |
| STEPLIB | program library | `LOADLIB` | `gnucobol/work/bin` | `COB_LIBRARY_PATH` |

## Data conversion

The ASCII files were used. The EBCDIC files were not needed.

- **Sequential input (DALYTRAN):** the program reads fixed 350-byte records with no line endings, so the script removes the line feeds. Every line in `dailytran.txt` is exactly 350 bytes.
- **Indexed files (VSAM KSDS):** `gnucobol/KSDSUTIL.cbl` loads each text file into a GnuCOBOL indexed file (Berkeley DB). It does the same job as the `IDCAMS REPRO` steps in `XREFFILE.jcl`, `ACCTFILE.jcl` and `TCATBALF.jcl`. It can also unload a file back to text for inspection.
- **Cleanup:** `tcatbal.txt` has Windows (CRLF) line endings, so the script removes the CR characters. The lines in `cardxref.txt` are 36 bytes instead of 50. The 14 missing bytes are the record's unused filler, and the loader pads them with spaces.
- **Signed amounts:** amounts are stored with the mainframe sign convention, where the last character of the number carries both the last digit and the sign. `{`/`A`–`I` mean +0..+9 and `}`/`J`–`R` mean -0..-9. The data is not changed. Instead the program is compiled with `-fsign=EBCDIC` so GnuCOBOL reads these signs the way the mainframe does. Without that flag amounts are decoded wrongly and the run rejects 299 of 300 transactions instead of 38. To see this, run `SIGN_ENCODING=ASCII ./run-cbtrn02c.sh` (the default is `EBCDIC`).

## Changes to the COBOL

**`CBTRN02C.cbl` and its copybooks are unchanged.** The program compiles cleanly with GnuCOBOL as-is. Two things are handled outside the source instead:

| Issue | Why | How it is handled |
|---|---|---|
| Signed numbers use mainframe sign characters | The data matches the original platform | Compile flag `-fsign=EBCDIC` |
| `CALL 'CEE3ABD'` (the z/OS routine that ends a program abnormally) doesn't exist on Linux | It is part of the mainframe runtime | Stand-in `gnucobol/CEE3ABD.cbl`, compiled as a module and found through `COB_LIBRARY_PATH` like a STEPLIB member. It reports `USER ABEND U0999` on stderr and exits with code 16. |

Both new programs (`KSDSUTIL`, `CEE3ABD`) are helpers. They are not part of the original application.

## Results with the sample data

```
TRANSACTIONS PROCESSED :000000300
TRANSACTIONS REJECTED  :000000038
CBTRN02C return code: 4
```

- **262 posted.** These were written to TRANSACT, all 50 accounts were updated, and the category balance file grew from 50 to 100 records. The 50 new records are the first return (type `03`) for each account, because the sample balance file only has purchase (type `01`) records.
- **38 rejected, all with reason `102 OVERLIMIT TRANSACTION`.** They are all purchases, spread across 17 accounts and totaling $26,846.84. No transaction failed the card lookup (100), the account lookup (101) or the expiration check (103).
- **Independent check:** a separate Python version of the same rules, run over the same input, produced the same posted/rejected split and the same final account and category balances.

## Java port and the automatic check

`java/` contains a Java 17 port of CBTRN02C. It keeps the original business rules exactly, including the over-limit formula described below, and produces the same output files as the COBOL run.

```bash
sudo apt-get install -y openjdk-17-jdk maven   # Ubuntu
brew install openjdk@17 maven                  # macOS
cd java && mvn package                         # builds java/target/cbtrn02c.jar and runs the unit tests
java -jar target/cbtrn02c.jar --input-dir ../app/data/ASCII --output-dir out
```

It reads the ASCII sample files directly (no conversion step) and writes `TRANSACT.txt`, `ACCTDATA.txt`, `TCATBALF.txt` and `DALYREJS.txt`, one record per line. It prints the same SYSOUT messages and uses the same return codes as the COBOL step: 0 all posted, 4 some rejected, 16 abend.

| COBOL paragraph | Java |
|---|---|
| `1500-VALIDATE-TRAN` (card, account, limit, expiration checks) | `TransactionValidator` |
| `2000-POST-TRANSACTION`, `2700-UPDATE-TCATBAL` | `DailyTransactionPosting` |
| `2800-UPDATE-ACCOUNT-REC` | `Account.post` |
| Indexed files (VSAM KSDS) | `KeyedFile` (in memory, kept in key order) |
| Signed amounts (`PIC S9(n)V99` with sign characters) | `ZonedDecimal` |

**Comparing the two:** `tests/compare-cbtrn02c.sh` runs the COBOL program and the Java port on the sample data and checks every output file from both against a saved copy of the COBOL results in `tests/golden/cbtrn02c/` (SYSOUT, transactions, accounts, category balances, rejects). It ignores trailing spaces and the processing timestamp in TRANSACT (columns 305-330), because that is the time of the run. Anything else that differs fails the check, and the script prints the differing lines. The Maven build also runs the same comparison for the Java port alone.

**Edge cases:** the sample data only produces over-limit rejects, so `tests/data/cbtrn02c-edge/` adds 23 hand-made transactions covering every reject reason and boundary. The cases include: unknown card (100), card whose account is missing (101), exactly at the limit vs one cent over (102), a return rejected as over limit, posting on the expiration date vs the day after (103), and 103 winning over 102. They also cover zero and largest amounts, an amount that overflows a balance field (both versions drop the high-order digits, as COBOL does), and new category balance records. Each case is described in `tests/cbtrn02c_data.py` (`edge()`), which also regenerates the files. The COBOL results are saved in `tests/golden/cbtrn02c-edge/`:

```bash
tests/compare-cbtrn02c.sh tests/data/cbtrn02c-edge tests/golden/cbtrn02c-edge
```

**Random batches:** `tests/fuzz-cbtrn02c.py [FIRST_SEED] [COUNT]` builds random batches (accounts, cards, category balances and up to 300 transactions each), runs both versions on each one and stops at the first difference. It prints the seed so the failing batch can be rebuilt with `tests/cbtrn02c_data.py random SEED DIR`. These are compared COBOL against Java directly, with no saved baseline. 200 batches take about 2 minutes.

**On every change:** the GitHub Actions workflow `.github/workflows/cbtrn02c.yml` runs the Maven build, both baseline comparisons and 200 random batches on every pull request and every push to `main`.

**If the results are meant to change** (for example, an agreed change to the over-limit rule), update the COBOL and Java code together. Then run `./run-cbtrn02c.sh` and copy the new files from `gnucobol/work/` into `tests/golden/cbtrn02c/`: `sysout.txt`, `rejects.txt` as `DALYREJS.txt`, and `data/<name>.after.txt` as `<name>.txt` for TRANSACT, ACCTDATA and TCATBALF.

## Next job in the chain: interest calculation (CBACT04C)

`app/jcl/INTCALC.jcl` runs `CBACT04C` with `PARM='2022071800'` on the category balances and accounts that CBTRN02C just updated. For each category balance it looks up the account's interest rate in the disclosure groups (`discgrp.txt`), falling back to the `DEFAULT` group. It writes one interest transaction per balance with a non-zero rate (`SYSTRAN`), and adds the interest to the account balance and zeroes the cycle credit and debit.

```bash
./run-chain.sh                  # CBTRN02C, then CBACT04C on its output
./run-cbact04c.sh               # CBACT04C alone (INPUT_DIR, WORK_DIR, PARM_DATE optional)
tests/compare-cbact04c.sh       # COBOL and Java vs tests/golden/cbact04c (chained run)
tests/fuzz-cbtrn02c.py 1 200 cbact04c
java -cp java/target/cbtrn02c.jar com.carddemo.posttran.IntcalcMain --input-dir DIR --output-dir DIR
```

**COBOL changes: none.** Platform stand-ins:
- `gnucobol/RUNPARM.cbl`: on z/OS the JCL `PARM` reaches the program through `PROCEDURE DIVISION USING` as a halfword length plus text. A GnuCOBOL executable can't receive it that way, so CBACT04C is built as a module and RUNPARM passes the PARM the same way.
- `KSDSUTIL LOAD XREFAIX`: CBACT04C reads the card cross-reference by account ID through the alternate index (`XREFFIL1` / `CARDXREF.VSAM.AIX`, non-unique). The loaded file carries that alternate key; with several cards per account the first one in card-number order is returned. `KSDSUTIL LOAD DISCGRP` loads the disclosure groups.

**Results (chained after CBTRN02C on the sample data):** 100 category balances read, 50 interest transactions written (purchases; returns have a 0% rate), 49 of 50 accounts updated. Every sample account has a blank group ID, so all rates come from `DEFAULT`.

**Behaviour kept as in the original (findings, not fixed):**
- **The last account in the file is never updated.** The program updates an account only when the next balance belongs to a different account. Its end-of-file branch can never run, so account 50 gets its interest transaction, but its balance and cycle totals are left as they were.
- Interest is charged on negative balances too (a negative interest transaction), and amounts are truncated, not rounded.
- `1400-COMPUTE-FEES` is an empty placeholder.

## Account file extract (CBACT01C)

`app/jcl/READACCT.jcl` (STEP05) runs `CBACT01C`. It reads every account in the account KSDS (300-byte records, key = 11-digit account ID, copybook `CVACT01Y`), displays it, and writes three extract files. It has no PARM and rejects nothing: it ends with return code 0, or abends with U0999 (exit code 16 locally) if a file can't be opened, read or written.

```bash
./run-cbact01c.sh                                     # INPUT_DIR, WORK_DIR optional
tests/compare-cbact01c.sh                             # COBOL and Java vs tests/golden/cbact01c
tests/compare-cbact01c.sh tests/data/cbact01c-edge tests/golden/cbact01c-edge
tests/compare-cbact01c.sh tests/data/cbact01c-empty tests/golden/cbact01c-empty
tests/compare-cbact01c.sh tests/data/cbact01c-abend tests/golden/cbact01c-abend 16
tests/fuzz-cbtrn02c.py 1 200 cbact01c
tests/cbact01c_view.py gnucobol/work-cbact01c/data   # readable view of the binary outputs
java -cp java/target/cbtrn02c.jar com.carddemo.posttran.Cbact01cMain --input-dir DIR --output-dir DIR
```

| DD | Use | JCL format | Local file (`WORK_DIR/data/`) |
|---|---|---|---|
| `ACCTFILE` | input, read in key order | VSAM KSDS, 300 bytes, key 11 bytes | `ACCTDATA.idx`, loaded from `acctdata.txt` with `KSDSUTIL LOAD ACCT` |
| `OUTFILE` | output | RECFM=FB, LRECL=107 | `OUTFILE.dat` |
| `ARRYFILE` | output | RECFM=FB, LRECL=110 | `ARRYFILE.dat` |
| `VBRCFILE` | output | RECFM=VB, LRECL=84 | `VBRCFILE.dat` |

**How the outputs are stored off-mainframe.** The three outputs are binary files with no line endings, compared byte for byte (no masking; nothing in them depends on the run time).
- *Text and zoned amounts* (`PIC X`, `PIC S9(10)V99`) are ASCII bytes. Signs stay as overpunch characters (`{`, `A`-`I`, `}`, `J`-`R`), the same as the input data, because the program is compiled with `-fsign=EBCDIC`.
- *COMP-3 amounts* (`PIC S9(10)V99 COMP-3`, 7 bytes) are real packed decimal: two digits per byte, sign in the last half-byte (`C` positive, `D` negative). For example 2525.00 is `00 00 00 02 52 50 0C`. Packed bytes don't depend on the character set, so they are the same bytes z/OS would write.
- *OUTFILE / ARRYFILE* are fixed-length records written back to back (107 / 110 bytes each).
- *VBRCFILE* records are preceded by a 4-byte header: a 2-byte big-endian length of the data and 2 zero bytes (GnuCOBOL's `COB_VARSEQ_FORMAT=0`, pinned in `run-cbact01c.sh`). A z/OS record descriptor word has the same layout but its length also counts the 4 header bytes (16 and 43 here instead of 12 and 39); block descriptor words are not represented.

**COBOL changes: none.** Platform stand-ins:
- `gnucobol/COBDATFT.cbl`: CBACT01C calls the assembler routine `app/asm/COBDATFT.asm` to reformat the reissue date. It can't run off-mainframe, so this COBOL module takes the same 80-byte parameter (`CODATECN`) and makes the same byte moves and checks as the assembler, including returning 0 even after `INVALID INPUT`.
- `gnucobol/CEE3ABD.cbl` (existing) for the abend; `KSDSUTIL LOAD ACCT` (existing) for the KSDS.

**Results on the sample data:** 50 accounts read; 50 OUTFILE records (5,350 bytes), 50 ARRYFILE records (5,500 bytes), 100 VBRCFILE records (2,950 bytes); return code 0.

**Tests:** the sample baseline; 14 hand-made accounts (`tests/data/cbact01c-edge`: zero, negative-zero and non-zero cycle debits, unsigned digits, maximum and negative amounts, odd and blank dates, status/group/filler values, short and CRLF lines, the highest account ID); an empty account file; a missing account file (abend, rc 16); and 200 random account files (2,966 accounts). All match byte for byte. JUnit: `AccountExtractBaselineTest`.

**Mutation check:** changing the cycle-debit rule in `AccountExtract.populateOutRecord` from "cycle debit = 0" to "cycle debit >= 0" still matched the sample data (every sample account has a zero cycle debit), but the edge-case comparison (OUTFILE differs) and the random batches (seed 1) both failed. Reverted.

**Behaviour kept as in the original (findings, not fixed):**
- **OUTFILE's cycle debit is not the account's cycle debit.** It is set to a fixed 2525.00 only when the account's cycle debit is zero. Otherwise the field keeps whatever the previous record left in the output area; for the first record that is binary zeros, which isn't a valid packed number.
- **ARRYFILE holds made-up values:** slots 1-2 are the balance with fixed debits 1005.00 and 1525.00, slot 3 is a fixed -1025.00 / -2500.00, slots 4-5 are zero.
- **The reissue date is reformatted with an odd call:** the program passes input type 2 (`YYYY-MM-DD`) and output type 2. The assembler accepts any output type except 1 and doesn't check the separators, so the date comes out as `YYYYMMDD` followed by 2 bytes left over from earlier calls (spaces here). A date without dashes loses digits (`20250520` becomes `202552`).
- **The output files are never closed** by the program (only ACCTFILE is); GnuCOBOL closes them at the end and logs `implicit CLOSE` warnings. Every account is displayed twice in SYSOUT (field by field, then as the full record).
- **Displaying an amount changes the record:** with `-fsign=EBCDIC`, GnuCOBOL rewrites the sign of each amount it displays, so an unsigned last digit becomes a positive overpunch (`5` becomes `E`) in the record displayed afterwards. The sample data always has signs, so this only shows with the edge and random data. The Java port does the same.

## Transaction detail report (CBTRN03C)

`app/jcl/TRANREPT.jcl` unloads the transaction master, sorts it (STEP05R), then runs `CBTRN03C` (STEP10R). The program prints the daily transaction report: one line per transaction, grouped by card, with an account total when the card changes, a page total and new headings about every 20 lines, and a grand total at the end. It has no PARM and rejects nothing: it ends with return code 0, or abends with U0999 (exit code 16 locally) if a file can't be opened or read, or a card, type or category is not found.

```bash
./run-cbtrn03c.sh                                     # INPUT_DIR, WORK_DIR, DATEPARM_DATES optional
tests/compare-cbtrn03c.sh                             # COBOL and Java vs tests/golden/cbtrn03c
tests/compare-cbtrn03c.sh tests/data/cbtrn03c-edge tests/golden/cbtrn03c-edge
tests/compare-cbtrn03c.sh tests/data/cbtrn03c-badcard tests/golden/cbtrn03c-badcard 16
tests/fuzz-cbtrn02c.py 1 200 cbtrn03c
tests/cbtrn03c_data.py edge tests/data                # regenerates every tests/data/cbtrn03c-* case
java -cp java/target/cbtrn02c.jar com.carddemo.posttran.Cbtrn03cMain --input-dir DIR --output-dir DIR
```

| DD | Use | JCL format | Local file (`WORK_DIR/data/`) |
|---|---|---|---|
| `TRANFILE` | input, the sorted transactions | sequential, 350 bytes | `TRANFILE.dat`, built from `transact.txt` (or `tranfile.txt` as is) |
| `CARDXREF` | card to account lookup | VSAM KSDS, 50 bytes, key = card (16) | `CARDXREF.idx`, `KSDSUTIL LOAD XREF` |
| `TRANTYPE` | type description lookup | VSAM KSDS, 60 bytes, key = type (2) | `TRANTYPE.idx`, `KSDSUTIL LOAD TRANTYPE` (new) |
| `TRANCATG` | category description lookup | VSAM KSDS, 60 bytes, key = type + category (6) | `TRANCATG.idx`, `KSDSUTIL LOAD TRANCATG` (new) |
| `DATEPARM` | report date range `YYYY-MM-DD YYYY-MM-DD` | sequential, 80 bytes | `DATEPARM.dat`, from `dateparm.txt` |
| `TRANREPT` | output, the report | RECFM=FB, LRECL=133 | `TRANREPT.dat`; `WORK_DIR/TRANREPT.txt` has one 133-byte record per line |

The report is plain text with no run-time timestamps, so the comparison only ignores trailing spaces. After an abend the partial report is kept and compared (z/OS would delete it: `DISP=(NEW,CATLG,DELETE)`).

**COBOL changes: none.** Platform stand-ins:
- *SORT step (STEP05R):* `run-cbtrn03c.sh` keeps transactions whose processing date (columns 305-314) is within the DATEPARM range and sorts them by card number (columns 263-278) with a stable sort, so equal cards keep the unload order. The Java port does the same in `Cbtrn03cMain.readTranFile`. A `tranfile.txt` input skips this step, to test what the program does with input the SORT would never give it.
- *Sample input:* the repo has no transaction-master sample, so the default input is CBTRN02C's saved posted transactions (`tests/golden/cbtrn02c/TRANSACT.txt`) with the date range `2026-10-27 2026-10-27`, the posting day of that baseline.
- `gnucobol/KSDSUTIL.cbl` gets two load types (`TRANTYPE`, `TRANCATG`) for the 60-byte lookup files; `gnucobol/CEE3ABD.cbl` (existing) for the abend.

**Results on the sample data:** 262 transactions; 451 report lines: 262 detail lines, 49 account totals, 15 page totals, grand total 78,720.36; return code 0.

**Tests:** the sample baseline and 10 hand-made cases in `tests/data/cbtrn03c-*`, each described in `tests/cbtrn03c_data.py` (`edge()`):
- `edge` (49 detail lines): first and last day of the range and the days just outside it, two cards on one account, zero, negative-zero, unsigned and largest amounts, totals that overflow, descriptions longer than the report columns, CRLF and short lookup lines, equal cards out of transaction-ID order, page breaks from detail lines and from account totals.
- `empty` (empty transaction file), `blankparm` (blank dates, no transactions), `blankcard` (a blank first card: no lookup, account ID prints as zeros), `stop` (an out-of-range date in the middle of already-sorted input): return code 0.
- `badcard`, `badtype`, `badcatg` (lookup failures after part of the report is written), `missing` (no transaction file), `noparm` (no DATEPARM file): abend, return code 16.

200 random batches (4,489 detail lines; 10 end in a lookup abend, 31 print no totals) also match. JUnit: `TransactionReportBaselineTest`.

**Mutation check:** making the account total break on account ID instead of card number (in `TransactionReport.run`) still matched the sample data, where each account has one card. The edge-case comparison failed (the two cards of account 901 got one total) and so did the random batches (seed 2). Reverted.

**Behaviour kept as in the original (findings, not fixed):**
- **The last account's total is never printed:** at end of file the program writes the page and grand totals but no account total.
- **The last transaction is counted twice:** at end of file its amount is added again to the page and grand totals. The sample grand total, 78,720.36, includes 765.66 twice; the transactions add up to 77,954.70.
- **A date outside the range silently ends the report:** the first such transaction stops the run with no page or grand total and return code 0. On the mainframe the SORT step filters by date first, which hides this.
- **An empty transaction file gives an empty report** (no headings, no totals) with return code 0, and so does an empty DATEPARM file.
- **A blank first card is never looked up:** it equals the initial current card, so no lookup happens and its account ID prints as zeros.
- **Blank dates are accepted:** a DATEPARM line of spaces with no transactions prints a page total and grand total of zero.
- **Totals are per card, not per account:** two cards on one account get separate "Account Total" lines.
- **Page length varies:** a new page starts only when the line counter is exactly a multiple of 20, and the 2-line account total can step past it, so pages on the sample report run from 20 to 60 lines.
- **Overflowing totals lose their high-order digits** (no ON SIZE ERROR); a negative zero amount prints as blank, like zero.
- **SYSOUT lists every transaction record in full**, and at end of file prints the last amount and the page total as debug lines (`TRAN-AMT`, `WS-PAGE-TOTAL`).

## Demo walkthrough

`./demo.sh` runs the CBTRN02C story one step at a time, pausing for Enter between steps (`DEMO_NO_PAUSE=1` runs straight through): the original COBOL (38 rejects), the Java port, the file-by-file comparison against the saved baseline (10 `MATCH` lines), then a deliberate mistake. Step 4 changes one business rule in a throwaway copy of the Java (a transaction exactly at the credit limit is rejected instead of posted) and reruns the edge-case comparison: every Java file `DIFFERS`, and the script names the one transaction that was wrongly rejected (`901 posted exactly at limit`, 400.00). The copy is then deleted; the real Java is never changed. Outputs go to `build/demo/`. It needs the same setup as the Java port above.

## Things to know

- **How the over-limit check works:** it computes `cycle credit - cycle debit + amount` and compares that with the credit limit. Returns are negative and are added to cycle debit, so subtracting the debit makes each return *increase* the amount compared with the limit. If returns were instead netted against purchases, 23 transactions would be rejected rather than 38. This is the original program's behavior and has not been changed.
- **Transaction master is replaced each run:** the program opens TRANSACT with `OPEN OUTPUT`, so it only contains the transactions posted in that run. That is also how the original is written.
- **Wrong status on one error:** if closing the rejects file fails, `9300-DALYREJS-CLOSE` displays `XREFFILE-STATUS` instead of `DALYREJS-STATUS`. This is an existing slip in the original code and was left as is.
- **Return code on abend:** the mainframe ends with user abend U0999. Locally the exit code is 16, because Linux exit codes only go up to 255.
