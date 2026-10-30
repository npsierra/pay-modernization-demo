# Threat model: npsierra/pay-modernization-demo

This is the security model Devin security scans use to prioritize findings for this repo. Edit it when the system, its attackers or its accepted risks change.

## System overview
This repo is a COBOL modernization showcase built on AWS CardDemo, an open-source mainframe credit card system. It has two layers:
1. **Base CardDemo** (`app/`): the original mainframe application, kept unchanged. It has CICS online screens (BMS maps in `app/bms`), VSAM files, batch COBOL with JCL (`app/jcl`, `app/proc`, `app/scheduler`), and optional Db2/IMS/MQ modules in `app/app-*`.
2. **Modernized batch path**: GnuCOBOL 3.2 runners (`run-*.sh`, `run-chain.sh`, `gnucobol/` platform stand-ins), a Java 17 port of the batch programs (`java/`), COBOL-vs-Java equivalence tests (`tests/`), the demo script (`demo.sh`) and GitHub Actions CI (`.github/workflows/cbtrn02c.yml`).

**User direction:** treat the whole repo as a production card payments system. The modernized Java/GnuCOBOL/CI path is the higher-priority part. The base CardDemo is fully in scope and its findings matter too. The audience is operators of a large COBOL-based contract payments platform, so findings that would apply to a real mainframe payments estate are relevant.

## Components
| Component | Code | Notes |
|---|---|---|
| **Online CICS app** | `app/cbl/CO*.cbl` (COSGN00C sign-on, COMEN01C/COADM01C menus, COUSR0xC user admin, COACT*/COCRD* account & card view/update, COBIL00C bill pay, COTRN0xC transactions, CORPT00C report submit), `app/bms`, `app/cpy-bms`, `app/csd` | Terminal users; authorization is by user type in the COMMAREA (`COCOM01Y.cpy`, `CDEMO-USRTYP-ADMIN`) |
| **Batch COBOL** | `app/cbl/CB*.cbl` (CBTRN01-03C posting/reports, CBACT01-04C accounts/interest, CBCUS01C, CBEXPORT/CBIMPORT, CBSTM03A/B statements), `app/jcl`, `app/proc`, `app/scheduler`, `app/asm`, `app/maclib` | Reads daily transaction files and updates VSAM masters |
| **Optional mainframe modules** | `app/app-authorization-ims-db2-mq` (MQ card authorization COPAUA0C, IMS DB, Db2 fraud table), `app/app-transaction-type-db2` (Db2 transaction type maintenance), `app/app-vsam-mq` (MQ account/date inquiry) | MQ messages are an entry point from other systems |
| **Java port** | `java/src/main/java/com/carddemo/posttran/` (Main/IntcalcMain/Cbact01cMain entry points, file parsers, ZonedDecimal/PackedDecimal) | CLI batch, input/output dirs passed as args |
| **GnuCOBOL runtime** | `gnucobol/` (KSDSUTIL, RUNPARM, CEE3ABD, COBDATFT stand-ins, install-gnucobol.sh), `run-*.sh`, `run-chain.sh`, `demo.sh` | Env-driven (`INPUT_DIR`, `WORK_DIR`, `SIGN_ENCODING`, `DD_*`) |
| **CI and tests** | `.github/workflows/cbtrn02c.yml`, `.github/dependabot.yml`, `tests/` | Runs on `pull_request` with read-only token, pinned action SHAs, `persist-credentials: false` |
| **Mainframe ops scripts** | `scripts/` (remote_compile/submit/refresh.sh, upld_module.sh, run_*.sh, local_compile.sh) | FTP (tnftp) over a local tunnel to a hosted mainframe (Ensono); not runnable off-mainframe |

## Trust boundaries and entry points
- **Terminal user -> CICS app**: sign-on with user ID and password checked against the USRSEC VSAM file (`COSGN00C.cbl` ~line 223, `CSUSR01Y.cpy`). Every screen trusts the COMMAREA after sign-on. Regular users vs admins (`A`/`U`) are separated in the menus (COMEN01C line ~136).
- **Batch input files -> batch COBOL / Java port**: daily transactions (`DALYTRAN`), account, card, xref, customer and category files. Whoever can supply or alter these files controls postings and balances.
- **MQ queues -> authorization / inquiry modules**: request messages from other systems.
- **CICS -> JES**: CORPT00C writes JCL to the `JOBS` TD queue (job submission from the online app).
- **Contributors -> repo / CI**: pull requests run CI (build, tests, fuzz) on GitHub-hosted runners; dependencies come from Maven Central and pinned GitHub Actions.
- **Operator workstation -> mainframe**: `scripts/remote_*.sh` push source/JCL over FTP to a tunnel on localhost:2121.

## Assets
- Cardholder data: card numbers, CVV, expiry, names, SSNs and addresses (`carddata`, `custdata`, `CVCUS01Y.cpy`, `CVACT02Y.cpy`). Sample data is **synthetic** (user confirmed), but the code paths are treated as handling real PANs (PCI-DSS scope).
- Account balances, credit limits, transaction postings and interest (integrity of money movement).
- User credentials and roles (USRSEC; sample admins seeded with `PASSWORDA` in `app/jcl/DUSRSECJ.jcl`).
- The Java port's equivalence with COBOL (a silent divergence is a financial-integrity issue).
- CI integrity and the repo supply chain.
- Mainframe access used by the ops scripts.

## Attackers (user: all of these matter)
1. **Outside contributor / supply chain**: malicious PRs, compromised GitHub Actions or Maven dependencies, CI abuse.
2. **Batch input supplier**: anyone who can supply or tamper with batch input files (upstream feed, operator, compromised transfer). Crafted records aim to bypass validation, corrupt balances, crash jobs or abuse parsers.
3. **Authenticated low-privilege CICS user**: escalation to admin functions, viewing or changing other customers' accounts and cards, abusing bill pay or report submission.
Implied as well: an unauthenticated terminal user attacking sign-on, and an MQ client sending crafted authorization messages.

## Existing controls
- CICS: password check at sign-on, user-type gating in menus, CICS transaction security assumed external (RACF, not in repo).
- Batch: CBTRN02C validation (xref lookup, account exists, credit limit, expiry), reject file with reasons.
- Java: same validation as COBOL, proven by golden compare, 23 targeted edge cases and a 200-seed fuzz in CI; numbers formatted with `Locale.ROOT`.
- CI: `permissions: contents: read`, actions pinned by SHA, `persist-credentials: false`, Dependabot for Actions and Maven.
- Scripts: `run-*.sh` refuse `WORK_DIR=/` and validate `SIGN_ENCODING`.

## Out of scope / accepted risk
- Nothing ruled out by the user. Documented business-rule quirks kept on purpose for COBOL fidelity (returns count against the credit limit in CBTRN02C; CBACT04C never updates the last account; the Java ports keep partial outputs on abend instead of the JCL `DISP=(NEW,CATLG,DELETE)` cleanup) are compatibility decisions, not new vulnerabilities, but they remain in scope if a finding shows security impact.

## Open questions
- Is RACF/CICS transaction security assumed for the base CardDemo, or should the app's own checks be judged alone? (Draft: judge the app's own checks.)
- Will the Java port ever run as a service or take input from untrusted sources, or does it stay as operator-run batch? (Draft: operator-run batch whose inputs are attacker-influenced, per attacker 2.)
