package com.carddemo.posttran;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point for the CBACT02C port:
 * {@code java -cp cbtrn02c.jar com.carddemo.posttran.Cbact02cMain --input-dir DIR}.
 * Reads carddata.txt from DIR and writes only SYSOUT. Exit codes match the COBOL step:
 * 0 normal end (an empty card file included), 16 abend (U0999): no card file (OPEN status 35),
 * a card file that cannot be read (OPEN status 30) or a malformed one. Lines longer than 150
 * bytes are cut to 150, as the KSDS load does.
 */
public final class Cbact02cMain {
    static final String CARDS = "carddata.txt";

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
        Path cardFile = Path.of(args[1]).resolve(CARDS);
        System.out.println("START OF EXECUTION OF PROGRAM CBACT02C");
        try {
            // 0000-CARDFILE-OPEN: GnuCOBOL OPEN INPUT gives status 35 for a missing file, 30 for an unreadable one.
            if (!Files.isRegularFile(cardFile)) {
                return openFailed("35");
            }
            if (!Files.isReadable(cardFile)) {
                return openFailed("30");
            }
            KeyedFile<String> cards = PostingFiles.readTruncatedRecords(cardFile, "CARDFILE", CardFileListing.RECORD_LENGTH,
                    CardFileListing.KEY_LENGTH);
            new CardFileListing(System.out::println).run(cards.records());
            // 9000-CARDFILE-CLOSE: nothing to release once the records are in memory.
            System.out.println("END OF EXECUTION OF PROGRAM CBACT02C");
            return 0;
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
            return abend();
        }
    }

    /** 0000-CARDFILE-OPEN failure, then 9910-DISPLAY-IO-STATUS for a numeric status. */
    private static int openFailed(String status) {
        System.out.println("ERROR OPENING CARDFILE");
        System.out.println("FILE STATUS IS: NNNN00" + status);
        return abend();
    }

    /** 9999-ABEND-PROGRAM: CEE3ABD with abend code 999. */
    private static int abend() {
        System.out.println("ABENDING PROGRAM");
        System.err.println("CBACT02C: ABEND U0999");
        return Main.ABEND;
    }
}
