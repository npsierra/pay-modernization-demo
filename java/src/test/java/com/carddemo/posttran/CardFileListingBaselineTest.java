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
 * Runs the CBACT02C port on the sample, edge-case, empty and missing card files and compares
 * its SYSOUT with the GnuCOBOL baselines in tests/golden/cbact02c*.
 */
class CardFileListingBaselineTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));

    @ParameterizedTest
    @CsvSource({"app/data/ASCII, cbact02c, 0", "tests/data/cbact02c-edge, cbact02c-edge, 0",
            "tests/data/cbact02c-empty, cbact02c-empty, 0", "tests/data/cbact02c-abend, cbact02c-abend, 16"})
    void matchesCobolBaseline(String input, String golden, int expectedRc) throws IOException {
        Path inputDir = Files.createDirectories(ROOT.resolve(input));
        ByteArrayOutputStream sysout = new ByteArrayOutputStream();
        int returnCode = run(inputDir, sysout);
        assertEquals(expectedRc, returnCode);
        Path goldenSysout = ROOT.resolve("tests/golden").resolve(golden).resolve("sysout.txt");
        assertEquals(normalize(Files.readAllLines(goldenSysout, StandardCharsets.ISO_8859_1)),
                normalize(sysout.toString(StandardCharsets.ISO_8859_1).lines().toList()));
    }

    @Test
    void abendsWhenTheCardFileCannotBeRead(@TempDir Path dir) throws IOException {
        Path cards = Files.writeString(dir.resolve(PostingFiles.CARDS), "0000000000000001\n");
        Files.setPosixFilePermissions(cards, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(cards), "running as root: the file stays readable");
        ByteArrayOutputStream sysout = new ByteArrayOutputStream();
        assertEquals(Main.ABEND, run(dir, sysout));
        assertEquals(String.join(System.lineSeparator(), "START OF EXECUTION OF PROGRAM CBACT02C",
                "ERROR OPENING CARDFILE", "FILE STATUS IS: NNNN0037", "ABENDING PROGRAM", ""),
                sysout.toString(StandardCharsets.ISO_8859_1));
    }

    @Test
    void treatsADirectoryAsAMissingCardFile(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve(PostingFiles.CARDS));
        ByteArrayOutputStream sysout = new ByteArrayOutputStream();
        assertEquals(Main.ABEND, run(dir, sysout));
        Path goldenSysout = ROOT.resolve("tests/golden/cbact02c-abend/sysout.txt");
        assertEquals(normalize(Files.readAllLines(goldenSysout, StandardCharsets.ISO_8859_1)),
                normalize(sysout.toString(StandardCharsets.ISO_8859_1).lines().toList()));
    }

    @Test
    void formatsFileStatusLikeTheCobol() {
        assertEquals("0035", CardFileListing.ioStatus04("35"));
        assertEquals("0010", CardFileListing.ioStatus04("10"));
        assertEquals("9100", CardFileListing.ioStatus04("9d"));
        assertEquals("9049", CardFileListing.ioStatus04("91"));
        assertEquals("3065", CardFileListing.ioStatus04("3A"));
    }

    private static int run(Path inputDir, ByteArrayOutputStream sysout) {
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(sysout, true, StandardCharsets.ISO_8859_1));
            return Cbact02cMain.run(new String[] {"--input-dir", inputDir.toString()});
        } finally {
            System.setOut(original);
        }
    }

    private static String normalize(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line.replaceAll(" +$", "")).append('\n');
        }
        return text.toString();
    }
}
