package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs the CBACT02C port on the sample, edge-case, empty and missing-file inputs and compares
 * its SYSOUT and return code with the GnuCOBOL baselines in tests/golden/cbact02c*.
 */
class CardFileListingBaselineTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));

    @ParameterizedTest
    @CsvSource({"app/data/ASCII, cbact02c, 0", "tests/data/cbact02c-edge, cbact02c-edge, 0",
            "tests/data/cbact02c-empty, cbact02c-empty, 0", "tests/data/cbact02c-abend, cbact02c-abend, 16"})
    void matchesCobolBaseline(String input, String golden, int expectedRc) throws IOException {
        StringBuilder sysout = new StringBuilder();
        assertEquals(expectedRc, run(ROOT.resolve(input), sysout));
        assertEquals(normalize(Files.readAllLines(ROOT.resolve("tests/golden").resolve(golden).resolve("sysout.txt"),
                StandardCharsets.ISO_8859_1)), normalize(sysout.toString().lines().toList()));
    }

    /** GnuCOBOL OPEN INPUT on a card file without read permission fails with status 30. */
    @Test
    void unreadableCardFileAbends(@TempDir Path dir) throws IOException {
        Path cards = Files.copy(ROOT.resolve("app/data/ASCII").resolve(Cbact02cMain.CARDS),
                dir.resolve(Cbact02cMain.CARDS));
        Files.setPosixFilePermissions(cards, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(cards), "running as root: permissions are not enforced");
        StringBuilder sysout = new StringBuilder();
        assertEquals(16, run(dir, sysout));
        assertEquals(normalize(List.of("START OF EXECUTION OF PROGRAM CBACT02C", "ERROR OPENING CARDFILE",
                "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM")), normalize(sysout.toString().lines().toList()));
    }

    /** KSDSUTIL LOAD cuts a line longer than 150 bytes to 150, so the card is listed, not an abend. */
    @Test
    void overlongRecordIsCut(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(Cbact02cMain.CARDS), "4".repeat(150) + "EXTRA\n", StandardCharsets.US_ASCII);
        StringBuilder sysout = new StringBuilder();
        assertEquals(0, run(dir, sysout));
        assertEquals(List.of("START OF EXECUTION OF PROGRAM CBACT02C", "4".repeat(150),
                "END OF EXECUTION OF PROGRAM CBACT02C"), sysout.toString().lines().toList());
    }

    private static int run(Path inputDir, StringBuilder sysout) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.ISO_8859_1));
            returnCode = Cbact02cMain.run(new String[] {"--input-dir", inputDir.toString()});
        } finally {
            System.setOut(original);
        }
        sysout.append(captured.toString(StandardCharsets.ISO_8859_1));
        return returnCode;
    }

    private static String normalize(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line.replaceAll(" +$", "")).append('\n');
        }
        return text.toString();
    }
}
