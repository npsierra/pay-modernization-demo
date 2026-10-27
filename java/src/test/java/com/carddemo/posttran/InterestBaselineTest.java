package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the CBACT04C port on CBTRN02C's saved output (the chained nightly run) and compares
 * it with the GnuCOBOL baseline in tests/golden/cbact04c.
 */
class InterestBaselineTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));
    private static final Path GOLDEN = ROOT.resolve("tests/golden/cbact04c");

    @Test
    void matchesCobolBaseline(@TempDir Path dir) throws IOException {
        Path input = Files.createDirectories(dir.resolve("input"));
        Path out = dir.resolve("out");
        Files.copy(ROOT.resolve("tests/golden/cbtrn02c/TCATBALF.txt"), input.resolve("tcatbal.txt"));
        Files.copy(ROOT.resolve("tests/golden/cbtrn02c/ACCTDATA.txt"), input.resolve("acctdata.txt"));
        Files.copy(ROOT.resolve("app/data/ASCII/cardxref.txt"), input.resolve("cardxref.txt"));
        Files.copy(ROOT.resolve("app/data/ASCII/discgrp.txt"), input.resolve("discgrp.txt"));

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.US_ASCII));
            returnCode = IntcalcMain.run(new String[] {"--input-dir", input.toString(), "--output-dir",
                    out.toString()}, Clock.fixed(Instant.parse("2026-01-02T03:04:05.67Z"), ZoneOffset.UTC));
        } finally {
            System.setOut(original);
        }
        assertEquals(0, returnCode);
        assertEquals(normalize(Files.readAllLines(GOLDEN.resolve("sysout.txt")), false),
                normalize(captured.toString(StandardCharsets.US_ASCII).lines().toList(), false));
        assertEquals(normalize(Files.readAllLines(GOLDEN.resolve("ACCTDATA.txt")), false),
                normalize(Files.readAllLines(out.resolve("ACCTDATA.txt")), false));
        assertEquals(normalize(Files.readAllLines(GOLDEN.resolve("SYSTRAN.txt")), true),
                normalize(Files.readAllLines(out.resolve("SYSTRAN.txt")), true));
        String first = Files.readAllLines(out.resolve("SYSTRAN.txt")).get(0);
        assertEquals("2022071800000001", first.substring(0, 16));
        assertEquals("2026-01-02-03.04.05.670000", first.substring(278, 304));
    }

    /** Drops trailing spaces; for SYSTRAN blanks the run-time timestamps (columns 279-330). */
    private static String normalize(List<String> lines, boolean maskTimestamps) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            String record = line;
            if (maskTimestamps && record.length() > 278) {
                String padded = String.format("%-350s", record);
                record = padded.substring(0, 278) + " ".repeat(52) + padded.substring(330);
            }
            text.append(record.stripTrailing()).append('\n');
        }
        return text.toString();
    }
}
