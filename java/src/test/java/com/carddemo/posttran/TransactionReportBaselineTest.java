package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the CBTRN03C port on CBTRN02C's saved posted transactions (the default input of
 * run-cbtrn03c.sh) and compares it with the GnuCOBOL baseline in tests/golden/cbtrn03c.
 */
class TransactionReportBaselineTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));
    private static final Path GOLDEN = ROOT.resolve("tests/golden/cbtrn03c");

    @Test
    void matchesCobolBaseline(@TempDir Path dir) throws IOException {
        Path input = Files.createDirectories(dir.resolve("input"));
        Path out = dir.resolve("out");
        Files.copy(ROOT.resolve("tests/golden/cbtrn02c/TRANSACT.txt"), input.resolve("transact.txt"));
        for (String name : List.of("cardxref.txt", "trantype.txt", "trancatg.txt")) {
            Files.copy(ROOT.resolve("app/data/ASCII/" + name), input.resolve(name));
        }
        Files.writeString(input.resolve("dateparm.txt"), "2026-10-27 2026-10-27\n");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.US_ASCII));
            returnCode = Cbtrn03cMain.run(new String[] {"--input-dir", input.toString(), "--output-dir",
                    out.toString()});
        } finally {
            System.setOut(original);
        }
        assertEquals(0, returnCode);
        assertEquals(normalize(Files.readAllLines(GOLDEN.resolve("sysout.txt"))),
                normalize(captured.toString(StandardCharsets.US_ASCII).lines().toList()));
        assertEquals(normalize(Files.readAllLines(GOLDEN.resolve("TRANREPT.txt"))),
                normalize(Files.readAllLines(out.resolve("TRANREPT.txt"))));
    }

    @Test
    void editsAmountsLikeCobolPictures() {
        assertEquals(" ".repeat(15), TransactionReport.edit(new BigDecimal("-0.00"), '+'));
        assertEquals("-           .05", TransactionReport.edit(new BigDecimal("-0.05"), '-'));
        assertEquals("+999,999,999.99", TransactionReport.edit(new BigDecimal("999999999.99"), '+'));
        assertEquals("      10,000.00", TransactionReport.edit(new BigDecimal("10000.00"), '-'));
        assertEquals("+     10,000.00", TransactionReport.edit(new BigDecimal("10000.00"), '+'));
    }

    private static String normalize(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line.stripTrailing()).append('\n');
        }
        return text.toString();
    }

    @Test
    void editsAmountsWithAsciiDigitsInAnyLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals("     123,456.78", TransactionReport.edit(new BigDecimal("123456.78"), '-'));
            assertEquals("0000012345{", ZonedDecimal.encode(new BigDecimal("1234.50"), 9, 2));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void unreadableTransactionFileAbends(@TempDir Path dir) throws IOException {
        Path input = Files.createDirectories(dir.resolve("input"));
        Path transactions = Files.writeString(input.resolve("transact.txt"), "x\n");
        Files.writeString(input.resolve("dateparm.txt"), "2026-10-27 2026-10-27\n");
        Files.setPosixFilePermissions(transactions, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(transactions), "running as root: permissions are not enforced");

        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.US_ASCII));
            returnCode = Cbtrn03cMain.run(new String[] {"--input-dir", input.toString(), "--output-dir",
                    dir.resolve("out").toString()});
        } finally {
            System.setOut(original);
        }
        assertEquals(Main.ABEND, returnCode);
    }
}
