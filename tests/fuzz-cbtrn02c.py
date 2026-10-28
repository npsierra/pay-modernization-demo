#!/usr/bin/env python3
"""Differential test: builds random CBTRN02C batches (tests/cbtrn02c_data.py), runs the
original COBOL (run-cbtrn02c.sh) and the Java port on each, and fails on the first output
that differs. Usage: fuzz-cbtrn02c.py [FIRST_SEED] [COUNT] [cbtrn02c|cbact04c|cbact01c].
cbact04c runs the interest calculation (run-cbact04c.sh vs IntcalcMain) on random
category balances instead; cbact01c the account extract (run-cbact01c.sh vs Cbact01cMain)
on random account files, comparing its binary outputs byte for byte.
Needs java/target/cbtrn02c.jar.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "java/target/cbtrn02c.jar"
OUT = ROOT / "build/cbtrn02c-fuzz"
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "tests"))
import cbtrn02c_data  # noqa: E402

PAIRS = [  # (name, COBOL output relative to WORK_DIR, Java output)
    ("sysout", "sysout.txt", "sysout.txt"),
    ("TRANSACT", "data/TRANSACT.after.txt", "TRANSACT.txt"),
    ("ACCTDATA", "data/ACCTDATA.after.txt", "ACCTDATA.txt"),
    ("TCATBALF", "data/TCATBALF.after.txt", "TCATBALF.txt"),
    ("DALYREJS", "rejects.txt", "DALYREJS.txt"),
]


def normalize(path, mask_timestamp):
    """Same rules as compare-cbtrn02c.sh: drop trailing spaces, blank TRANSACT cols 305-330."""
    lines = []
    for line in path.read_text(encoding="ascii").splitlines():
        if mask_timestamp and len(line) > 304:
            line = line.ljust(350)
            line = line[:304] + " " * 26 + line[330:]
        lines.append(line.rstrip(" "))
    return lines


INTCALC_PAIRS = [
    ("sysout", "sysout.txt", "sysout.txt"),
    ("ACCTDATA", "data/ACCTDATA.after.txt", "ACCTDATA.txt"),
    ("SYSTRAN", "SYSTRAN.txt", "SYSTRAN.txt"),
]


def normalize_systran(path):
    """SYSTRAN: drop trailing spaces, blank the two run-time timestamps (cols 279-330)."""
    lines = []
    for line in path.read_text(encoding="ascii").splitlines():
        if len(line) > 278:
            line = line.ljust(350)
            line = line[:278] + " " * 52 + line[330:]
        lines.append(line.rstrip(" "))
    return lines


def run_intcalc_seed(seed):
    case = OUT / f"cbact04c-seed-{seed}"
    shutil.rmtree(case, ignore_errors=True)
    data, cobol, java = case / "input", case / "cobol", case / "java"
    cbtrn02c_data.write_intcalc(data, *cbtrn02c_data.intcalc_batch(seed))
    cobol_rc = subprocess.run([str(ROOT / "run-cbact04c.sh")], env={**os.environ, "INPUT_DIR": str(data),
                              "WORK_DIR": str(cobol)}, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL).returncode
    java.mkdir(parents=True)
    with open(java / "sysout.txt", "w") as sysout:
        java_rc = subprocess.run(["java", "-cp", str(JAR), "com.carddemo.posttran.IntcalcMain", "--input-dir",
                                  str(data), "--output-dir", str(java)], stdout=sysout,
                                 stderr=subprocess.DEVNULL).returncode
    problems = []
    if cobol_rc != java_rc:
        problems.append(f"return code COBOL {cobol_rc} vs Java {java_rc}")
    if cobol_rc != 0:
        problems.append(f"COBOL failed with return code {cobol_rc}")
    if not problems:
        for name, cobol_file, java_file in INTCALC_PAIRS:
            read = normalize_systran if name == "SYSTRAN" else (lambda f: normalize(f, False))
            if read(cobol / cobol_file) != read(java / java_file):
                problems.append(f"{name} differs ({cobol / cobol_file} vs {java / java_file})")
    if problems:
        return problems, 0
    written = len(normalize_systran(cobol / "SYSTRAN.txt"))
    shutil.rmtree(case)
    return problems, written


EXTRACT_FILES = ["OUTFILE.dat", "ARRYFILE.dat", "VBRCFILE.dat"]


def run_extract_seed(seed):
    case = OUT / f"cbact01c-seed-{seed}"
    shutil.rmtree(case, ignore_errors=True)
    data, cobol, java = case / "input", case / "cobol", case / "java"
    accounts = cbtrn02c_data.extract_batch(seed)
    cbtrn02c_data.write_extract(data, accounts)
    cobol_rc = subprocess.run([str(ROOT / "run-cbact01c.sh")], env={**os.environ, "INPUT_DIR": str(data),
                              "WORK_DIR": str(cobol)}, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL).returncode
    java.mkdir(parents=True)
    with open(java / "sysout.txt", "w") as sysout:
        java_rc = subprocess.run(["java", "-cp", str(JAR), "com.carddemo.posttran.Cbact01cMain", "--input-dir",
                                  str(data), "--output-dir", str(java)], stdout=sysout,
                                 stderr=subprocess.DEVNULL).returncode
    problems = []
    if cobol_rc != java_rc:
        problems.append(f"return code COBOL {cobol_rc} vs Java {java_rc}")
    if cobol_rc != 0:
        problems.append(f"COBOL failed with return code {cobol_rc}")
    if not problems:
        if normalize(cobol / "sysout.txt", False) != normalize(java / "sysout.txt", False):
            problems.append(f"sysout differs ({cobol / 'sysout.txt'} vs {java / 'sysout.txt'})")
        for name in EXTRACT_FILES:
            if (cobol / "data" / name).read_bytes() != (java / name).read_bytes():
                problems.append(f"{name} differs ({cobol / 'data' / name} vs {java / name})")
    if problems:
        return problems, 0
    shutil.rmtree(case)
    return problems, len(accounts)


def run_seed(seed):
    case = OUT / f"seed-{seed}"
    shutil.rmtree(case, ignore_errors=True)
    data, cobol, java = case / "input", case / "cobol", case / "java"
    cbtrn02c_data.write(data, *cbtrn02c_data.random_batch(seed))
    cobol_rc = subprocess.run([str(ROOT / "run-cbtrn02c.sh")], env={**os.environ, "INPUT_DIR": str(data),
                              "WORK_DIR": str(cobol)}, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL).returncode
    java.mkdir(parents=True)
    with open(java / "sysout.txt", "w") as sysout:
        java_rc = subprocess.run(["java", "-jar", str(JAR), "--input-dir", str(data), "--output-dir",
                                  str(java)], stdout=sysout, stderr=subprocess.DEVNULL).returncode
    problems = []
    if cobol_rc != java_rc:
        problems.append(f"return code COBOL {cobol_rc} vs Java {java_rc}")
    if cobol_rc not in (0, 4):
        problems.append(f"COBOL failed with return code {cobol_rc}")
    if not problems:
        for name, cobol_file, java_file in PAIRS:
            mask = name == "TRANSACT"
            if normalize(cobol / cobol_file, mask) != normalize(java / java_file, mask):
                problems.append(f"{name} differs ({cobol / cobol_file} vs {java / java_file})")
    if problems:
        return problems, 0
    rejects = len(normalize(cobol / "rejects.txt", False))
    shutil.rmtree(case)
    return problems, rejects


def main():
    first = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 100
    job = sys.argv[3] if len(sys.argv) > 3 else "cbtrn02c"
    jobs = {"cbtrn02c": (run_seed, "rejects"), "cbact04c": (run_intcalc_seed, "interest transactions"),
            "cbact01c": (run_extract_seed, "accounts extracted")}
    if job not in jobs:
        sys.exit(__doc__)
    run, noun = jobs[job]
    if not JAR.exists():
        sys.exit(f"{JAR} not found: run 'mvn package' in java/ first")
    rejects = 0
    for seed in range(first, first + count):
        problems, seed_rejects = run(seed)
        if problems:
            print(f"seed {seed}: MISMATCH")
            for problem in problems:
                print(f"  {problem}")
            print(f"Reproduce: tests/fuzz-cbtrn02c.py {seed} 1 {job} (inputs kept under {OUT})")
            sys.exit(1)
        rejects += seed_rejects
    print(f"{job}: {count} random batches (seeds {first}-{first + count - 1}): COBOL and Java identical "
          f"({rejects} {noun} in total)")


if __name__ == "__main__":
    main()
