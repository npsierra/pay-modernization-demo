package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs the CBACT01C port on the sample, edge-case, empty and missing account files and
 * compares it byte for byte with the GnuCOBOL baselines in tests/golden/cbact01c*.
 */
class AccountExtractBaselineTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));
    private static final List<String> FILES = List.of("OUTFILE.dat", "ARRYFILE.dat", "VBRCFILE.dat");

    @ParameterizedTest
    @CsvSource({"app/data/ASCII, cbact01c, 0", "tests/data/cbact01c-edge, cbact01c-edge, 0",
            "tests/data/cbact01c-empty, cbact01c-empty, 0", "tests/data/cbact01c-abend, cbact01c-abend, 16"})
    void matchesCobolBaseline(String input, String golden, int expectedRc, @TempDir Path dir) throws IOException {
        Path inputDir = Files.createDirectories(ROOT.resolve(input));
        Path goldenDir = ROOT.resolve("tests/golden").resolve(golden);
        Path out = dir.resolve("out");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.ISO_8859_1));
            returnCode = Cbact01cMain.run(new String[] {"--input-dir", inputDir.toString(), "--output-dir",
                    out.toString()});
        } finally {
            System.setOut(original);
        }
        assertEquals(expectedRc, returnCode);
        assertEquals(normalize(Files.readAllLines(goldenDir.resolve("sysout.txt"), StandardCharsets.ISO_8859_1)),
                normalize(captured.toString(StandardCharsets.ISO_8859_1).lines().toList()));
        for (String name : FILES) {
            if (Files.exists(goldenDir.resolve(name))) {
                assertArrayEquals(Files.readAllBytes(goldenDir.resolve(name)), Files.readAllBytes(out.resolve(name)),
                        name);
            } else {
                assertFalse(Files.exists(out.resolve(name)), name);
            }
        }
    }

    @Test
    void packsSignedAmounts() {
        assertArrayEquals(new byte[] {0, 0, 0, 0x02, 0x52, 0x50, 0x0C},
                PackedDecimal.encode(new BigDecimal("2525.00"), 10, 2));
        assertArrayEquals(new byte[] {0, 0, 0, 0x02, 0x50, 0x00, 0x0D},
                PackedDecimal.encode(new BigDecimal("-2500.00"), 10, 2));
    }

    @Test
    void reformatsDashedDateAndKeepsTheRestOfTheOutputArea() {
        char[] rec = DateFormatRoutine.newRecord();
        rec[DateFormatRoutine.IN_TYPE] = '2';
        rec[DateFormatRoutine.OUT_TYPE] = '2';
        DateFormatRoutine.setInputDate(rec, "2025-05-20");
        DateFormatRoutine.call(rec);
        assertEquals("20250520" + " ".repeat(12), DateFormatRoutine.outputDate(rec));
    }

    private static String normalize(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line.replaceAll(" +$", "")).append('\n');
        }
        return text.toString();
    }
}
