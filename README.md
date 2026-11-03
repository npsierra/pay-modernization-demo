# Pay modernization demo

Demonstration of modernizing legacy COBOL applications off-mainframe with GnuCOBOL and Java 17 using AWS [CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo), with COBOL and Java proven equivalent on every PR.

For each CardDemo batch program:

1. Run the original COBOL, unchanged, on Linux or macOS with GnuCOBOL 3.2.
2. Save its output as the baseline.
3. Port the program to Java 17.
4. Prove the COBOL and Java produce identical files on the sample data, targeted edge cases and 200 random batches. A GitHub check repeats this on every pull request.

Programs done so far: CBTRN02C (daily transaction posting), CBACT04C (interest calculation), CBACT01C (account file extract) and CBTRN01C (daily transaction verification). Details, results and findings for each are in [RUNNING.md](RUNNING.md).

## Get the code

```bash
git clone https://github.com/npsierra/pay-modernization-demo.git
cd pay-modernization-demo
```

## Install on macOS

Needs [Homebrew](https://brew.sh). Then, in Terminal (from the repo folder):

```bash
brew install gnucobol openjdk@17 maven
printf '%s\n' "export PATH=$(brew --prefix openjdk@17)/bin:\$PATH" "export JAVA_HOME=$(brew --prefix openjdk@17)" >> ~/.zshrc
```

Open a new Terminal window and check the versions:

```bash
cobc --version | head -1   # cobc (GnuCOBOL) 3.2.0
java -version              # openjdk version "17..."
mvn -version
```

Homebrew doesn't put Java 17 on the PATH by itself, so the second line above does it; `brew --prefix` gives the right folder on both Apple Silicon (`/opt/homebrew`) and Intel (`/usr/local`) Macs. You can skip the `sudo ln` symlink and `CPPFLAGS` hints that Homebrew prints; nothing here needs them.

## Install on Ubuntu

```bash
sudo apt-get install -y openjdk-17-jdk maven python3
gnucobol/install-gnucobol.sh   # installs its build tools, then builds GnuCOBOL 3.2 (apt only has 3.1.2)
```

## Run it

```bash
./run-cbtrn02c.sh              # original COBOL: posts 300 sample transactions, 38 rejected
(cd java && mvn -B package)    # builds the Java and runs its unit tests
./demo.sh                      # six steps: COBOL, Java, file comparison, a mistake the sample data misses and the edge cases catch
tests/compare-cbtrn02c.sh      # COBOL vs Java vs baseline on the sample data
```

The full list of checks the GitHub workflow runs is in [.github/workflows/cbtrn02c.yml](.github/workflows/cbtrn02c.yml).

---

## About CardDemo

The COBOL under `app/` is AWS's [CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo), unchanged, licensed under Apache 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). Its original README, diagrams, contributing guide and mainframe scripts are under [carddemo/](carddemo/). The three sections below are taken from that README.

### Executive Summary
CardDemo is a comprehensive mainframe application that simulates a credit card management system. Designed specifically to showcase AWS and partner technologies for mainframe migration and modernization scenarios, it provides a realistic environment for testing various modernization approaches including discovery, migration, performance testing, service enablement, and more.

### Description
CardDemo is a mainframe application designed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases. It provides a realistic environment for:

- Application discovery and analysis
- Migration assessment and planning
- Modernization strategy development
- Performance testing
- System augmentation
- Service enablement and extraction
- Test creation and automation

The application intentionally incorporates various coding styles and patterns to exercise analysis, transformation, and migration tooling across different mainframe programming paradigms.

### Technologies

#### Core Technologies
- **COBOL**: Primary programming language
- **CICS**: Transaction processing
- **VSAM (KSDS with AIX)**: Data storage
- **JCL**: Batch processing
- **RACF**: Security
- **ASSEMBLER**: System-level programming
  - MVSWAIT: Timer control for batch jobs
  - COBDATFT: Date format conversion utility

#### Optional Technologies
- **Db2**: Relational database management
- **IMS DB**: Hierarchical database
- **MQ**: Message queuing
- **JCL Utilities**: FTP, TXT2PDF, DB2 LOAD/UNLOAD, IMS DB LOAD/UNLOAD, Internal Reader
- **Advanced Data Formats**: COMP, COMP-3, Zoned Decimal, Signed, Unsigned
- **Additional Dataset Types**: VSAM (ESDS/RRDS), GDG, PDS
- **Record Formats**: VB, FBA, and others
- **Complex Copybook Structures**: REDEFINES, OCCURS, OCCURS DEPENDING ON
