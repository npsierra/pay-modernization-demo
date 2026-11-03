package com.carddemo.posttran;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point for the CBACT02C port:
 * {@code java -cp cbtrn02c.jar com.carddemo.posttran.Cbact02cMain --input-dir DIR}.
 * Reads carddata.txt and lists it on standard output (SYSOUT); writes no files. Exit codes
 * match the COBOL step: 0 normal end, 16 abend (e.g. no card file, as OPEN status 35).
 */
public final class Cbact02cMain {
    /** GnuCOBOL OPEN statuses: 35 file not found, 37 no permission to open. */
    private static final String NOT_FOUND = "35";
    private static final String NO_PERMISSION = "37";

    private Cbact02cMain() {
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.ISO_8859_1));
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length != 2 || !"--input-dir".equals(args[0])) {
            System.err.println("Usage: java -cp cbtrn02c.jar com.carddemo.posttran.Cbact02cMain --input-dir DIR");
            return 8;
        }
        Path file = Path.of(args[1]).resolve(PostingFiles.CARDS);
        try {
            return new CardFileListing(System.out::println).run(open(file));
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
            System.out.println("ABENDING PROGRAM");
            System.err.println("CBACT02C: ABEND U0999");
            return Main.ABEND;
        }
    }

    /**
     * CARDFILE after the KSDS load: records in key order (a duplicate key abends, as the load
     * fails with status 22). A missing file opens with status 35, an unreadable one with 37.
     */
    private static CardFileListing.CardFile open(Path file) {
        if (!Files.exists(file)) {
            return new CardFileListing.CardFile(NOT_FOUND, List.of());
        }
        List<String> records;
        try {
            records = PostingFiles.readLoadedRecords(file, CardFileListing.RECORD_LENGTH);
        } catch (IOException e) {
            System.err.println("CBACT02C: " + e);
            return new CardFileListing.CardFile(NO_PERMISSION, List.of());
        }
        KeyedFile<String> cards = new KeyedFile<>("CARDFILE", record -> record.substring(0, 16));
        records.forEach(cards::write);
        return new CardFileListing.CardFile("00", List.copyOf(cards.records()));
    }
}
