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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs the Java port and compares every output with the baseline captured from the GnuCOBOL
 * run of the original program: the repository's sample data (tests/golden/cbtrn02c) and the
 * hand-made edge cases (tests/data/cbtrn02c-edge, tests/golden/cbtrn02c-edge).
 */
class BaselineEquivalenceTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));

    @ParameterizedTest
    @CsvSource({"app/data/ASCII, tests/golden/cbtrn02c", "tests/data/cbtrn02c-edge, tests/golden/cbtrn02c-edge"})
    void matchesCobolBaseline(String inputDir, String goldenDir, @TempDir Path out) throws IOException {
        Path input = ROOT.resolve(inputDir);
        Path golden = ROOT.resolve(goldenDir);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.US_ASCII));
            returnCode = Main.run(new String[] {"--input-dir", input.toString(), "--output-dir", out.toString()},
                    Clock.fixed(Instant.parse("2026-01-02T03:04:05.67Z"), ZoneOffset.UTC));
        } finally {
            System.setOut(original);
        }

        assertEquals(4, returnCode);
        assertEquals(normalize(Files.readAllLines(golden.resolve("sysout.txt")), false),
                normalize(captured.toString(StandardCharsets.US_ASCII).lines().toList(), false));
        for (String file : List.of(PostingFiles.ACCOUNTS_OUT, PostingFiles.CATEGORY_BALANCES_OUT,
                PostingFiles.REJECTS_OUT, PostingFiles.TRANSACTIONS_OUT)) {
            boolean transactions = file.equals(PostingFiles.TRANSACTIONS_OUT);
            assertEquals(normalize(Files.readAllLines(golden.resolve(file)), transactions),
                    normalize(Files.readAllLines(out.resolve(file)), transactions), file);
        }
        assertEquals("2026-01-02-03.04.05.670000",
                Files.readAllLines(out.resolve(PostingFiles.TRANSACTIONS_OUT)).get(0).substring(304, 330));
    }

    /**
     * Trailing spaces are dropped (the COBOL text unload trims them) and, for TRANSACT, the
     * run-time processing timestamp (columns 305-330) is blanked.
     */
    private static String normalize(List<String> lines, boolean maskProcessingTimestamp) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            String record = line;
            if (maskProcessingTimestamp && record.length() > 304) {
                String padded = String.format("%-350s", record);
                record = padded.substring(0, 304) + " ".repeat(26) + padded.substring(330);
            }
            text.append(record.stripTrailing()).append('\n');
        }
        return text.toString();
    }
}
