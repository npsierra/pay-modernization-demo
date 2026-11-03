package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs the CBTRN01C port on the sample, edge-case, empty and missing-file inputs and compares
 * its SYSOUT and return code with the GnuCOBOL baselines in tests/golden/cbtrn01c*.
 */
class TransactionVerificationBaselineTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));

    @ParameterizedTest
    @CsvSource({"app/data/ASCII, cbtrn01c, 0", "tests/data/cbtrn01c-edge, cbtrn01c-edge, 0",
            "tests/data/cbtrn01c-empty, cbtrn01c-empty, 0", "tests/data/cbtrn01c-abend, cbtrn01c-abend, 16",
            "tests/data/cbtrn01c-no-custdata, cbtrn01c-no-custdata, 16"})
    void matchesCobolBaseline(String input, String golden, int expectedRc) throws IOException {
        Path inputDir = ROOT.resolve(input);
        Path transact = Files.exists(inputDir.resolve("transact.txt")) ? inputDir.resolve("transact.txt")
                : ROOT.resolve("tests/golden/cbtrn02c/TRANSACT.txt");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.ISO_8859_1));
            returnCode = Cbtrn01cMain.run(new String[] {"--input-dir", inputDir.toString(), "--transact-file",
                    transact.toString()});
        } finally {
            System.setOut(original);
        }
        assertEquals(expectedRc, returnCode);
        assertEquals(normalize(Files.readAllLines(ROOT.resolve("tests/golden").resolve(golden).resolve("sysout.txt"),
                StandardCharsets.ISO_8859_1)), normalize(captured.toString(StandardCharsets.ISO_8859_1).lines().toList()));
    }

    private static String normalize(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line.replaceAll(" +$", "")).append('\n');
        }
        return text.toString();
    }
}
