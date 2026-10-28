# Running CBTRN02C (daily transaction posting) with GnuCOBOL

This runs the batch program `app/cbl/CBTRN02C.cbl` on Linux with GnuCOBOL. It uses the sample data in `app/data/ASCII` and needs no mainframe.

## Quick start

```bash
sudo apt-get install -y gnucobol   # Ubuntu 22.04: GnuCOBOL 3.1.2
brew install gnucobol              # macOS: GnuCOBOL 3.2
./run-cbtrn02c.sh
```

Tested with GnuCOBOL 3.1.2 and 3.2 (the version Homebrew installs); both give identical results. The script compiles everything and rebuilds the input files from `app/data/ASCII`, so each run starts from the same data. It then runs the program and prints a summary. Everything it creates goes in `gnucobol/work/`, which git ignores:

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
- **Signed amounts:** amounts are stored with the mainframe sign convention, where the last character of the number carries both the last digit and the sign. `{`/`A`–`I` mean +0..+9 and `}`/`J`–`R` mean -0..-9. The data is not changed. Instead the program is compiled with `-fsign=EBCDIC` so GnuCOBOL reads these signs the way the mainframe does. Without that flag the run produces 43 rejects instead of 38, because amounts are decoded wrongly.

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

**On every change:** the GitHub Actions workflow `.github/workflows/cbtrn02c.yml` runs the Maven build and `tests/compare-cbtrn02c.sh` on every pull request and every push to `main`.

**If the results are meant to change** (for example, an agreed change to the over-limit rule), update the COBOL and Java code together. Then run `./run-cbtrn02c.sh` and copy the new files from `gnucobol/work/` into `tests/golden/cbtrn02c/`: `sysout.txt`, `rejects.txt` as `DALYREJS.txt`, and `data/<name>.after.txt` as `<name>.txt` for TRANSACT, ACCTDATA and TCATBALF.

## Things to know

- **How the over-limit check works:** it computes `cycle credit - cycle debit + amount` and compares that with the credit limit. Returns are negative and are added to cycle debit, so subtracting the debit makes each return *increase* the amount compared with the limit. If returns were instead netted against purchases, 23 transactions would be rejected rather than 38. This is the original program's behavior and has not been changed.
- **Transaction master is replaced each run:** the program opens TRANSACT with `OPEN OUTPUT`, so it only contains the transactions posted in that run. That is also how the original is written.
- **Wrong status on one error:** if closing the rejects file fails, `9300-DALYREJS-CLOSE` displays `XREFFILE-STATUS` instead of `DALYREJS-STATUS`. This is an existing slip in the original code and was left as is.
- **Return code on abend:** the mainframe ends with user abend U0999. Locally the exit code is 16, because Linux exit codes only go up to 255.
