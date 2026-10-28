#!/usr/bin/env python3
"""Differential test: builds random CBTRN02C batches (tests/cbtrn02c_data.py), runs the
original COBOL (run-cbtrn02c.sh) and the Java port on each, and fails on the first output
that differs. Usage: fuzz-cbtrn02c.py [FIRST_SEED] [COUNT]. Needs java/target/cbtrn02c.jar.
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
    if not JAR.exists():
        sys.exit(f"{JAR} not found: run 'mvn package' in java/ first")
    rejects = 0
    for seed in range(first, first + count):
        problems, seed_rejects = run_seed(seed)
        if problems:
            print(f"seed {seed}: MISMATCH")
            for problem in problems:
                print(f"  {problem}")
            print(f"Reproduce: tests/fuzz-cbtrn02c.py {seed} 1 (inputs kept in {OUT}/seed-{seed})")
            sys.exit(1)
        rejects += seed_rejects
    print(f"{count} random batches (seeds {first}-{first + count - 1}): COBOL and Java identical "
          f"({rejects} rejects in total)")


if __name__ == "__main__":
    main()
