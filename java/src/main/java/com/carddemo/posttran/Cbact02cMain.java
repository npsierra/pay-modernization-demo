package com.carddemo.posttran;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point for the CBACT02C port:
 * {@code java -cp cbtrn02c.jar com.carddemo.posttran.Cbact02cMain --input-dir DIR}.
 * Reads carddata.txt and writes the listing (the job's SYSOUT) to standard output. Exit codes
 * match the COBOL step: 0 normal end, 16 abend (no card file: OPEN status 35; a file that
 * can't be read: status 37).
 */
public final class Cbact02cMain {
    private Cbact02cMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length != 2 || !args[0].equals("--input-dir")) {
            System.err.println("Usage: java -cp cbtrn02c.jar com.carddemo.posttran.Cbact02cMain --input-dir DIR");
            return 8;
        }
        Path file = Path.of(args[1]).resolve(PostingFiles.CARDS);
        // Card text is passed through byte for byte, as GnuCOBOL's DISPLAY does.
        PrintStream sysout = new PrintStream(System.out, true, StandardCharsets.ISO_8859_1);
        try {
            List<String> cards = List.of();
            String openStatus = "00";
            if (!Files.isRegularFile(file)) {
                openStatus = CardFileListing.FILE_NOT_FOUND;
            } else {
                try {
                    cards = loadKsds(PostingFiles.readLoadedRecords(file, CardFileListing.RECORD_LENGTH));
                } catch (IOException e) {
                    System.err.println("CBACT02C: " + e);
                    openStatus = CardFileListing.PERMISSION_DENIED;
                }
            }
            new CardFileListing(cards, openStatus, sysout::println).run();
            return 0;
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            return Main.ABEND;
        } finally {
            sysout.flush();
        }
    }

    /** Stand-in for KSDSUTIL LOAD CARD: records in key order; a duplicate card number abends. */
    private static List<String> loadKsds(List<String> records) {
        KeyedFile<String> ksds = new KeyedFile<>("CARDFILE", record -> record.substring(0, 16));
        records.forEach(ksds::write);
        return List.copyOf(ksds.records());
    }
}
