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
        Run run = run(inputDir);
        assertEquals(expectedRc, run.returnCode);
        assertEquals(normalize(Files.readAllLines(ROOT.resolve("tests/golden").resolve(golden).resolve("sysout.txt"),
                StandardCharsets.ISO_8859_1)), normalize(run.sysout.lines().toList()));
    }

    @Test
    void unreadableCardFileAbendsAtOpen(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve(PostingFiles.CARDS), "4000000000000001\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(file), "running as root: the file stays readable");
        Run run = run(dir);
        assertEquals(Main.ABEND, run.returnCode);
        assertEquals(lines("START OF EXECUTION OF PROGRAM CBACT02C", "ERROR OPENING CARDFILE",
                "FILE STATUS IS: NNNN0037", "ABENDING PROGRAM"), run.sysout);
    }

    @Test
    void directoryNamedLikeTheCardFileIsAMissingFile(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve(PostingFiles.CARDS));
        Run run = run(dir);
        assertEquals(Main.ABEND, run.returnCode);
        assertEquals(lines("START OF EXECUTION OF PROGRAM CBACT02C", "ERROR OPENING CARDFILE",
                "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM"), run.sysout);
    }

    @Test
    void loadsTextLikeKsdsutil(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cards.txt");
        Files.writeString(file, "\nA\r\n" + "B".repeat(500) + "\n" + "C".repeat(501) + "\nD", StandardCharsets.US_ASCII);
        List<String> records = PostingFiles.readLoadedRecords(file, 5);
        assertEquals(List.of("     ", "A    ", "BBBBB", "CCCCC", "C    ", "D    "), records);
    }

    @Test
    void showsNonNumericAndNineStatusesAsCodes() {
        assertEquals("0035", CardFileListing.formatIoStatus("35"));
        assertEquals("9065", CardFileListing.formatIoStatus("9A"));
        assertEquals("9055", CardFileListing.formatIoStatus("97"));
        assertEquals("A066", CardFileListing.formatIoStatus("AB"));
    }

    private static Run run(Path inputDir) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int returnCode;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.ISO_8859_1));
            returnCode = Cbact02cMain.run(new String[] {"--input-dir", inputDir.toString()});
        } finally {
            System.setOut(original);
        }
        return new Run(returnCode, captured.toString(StandardCharsets.ISO_8859_1));
    }

    private record Run(int returnCode, String sysout) {
    }

    private static String lines(String... lines) {
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String normalize(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line.replaceAll(" +$", "")).append('\n');
        }
        return text.toString();
    }
}
