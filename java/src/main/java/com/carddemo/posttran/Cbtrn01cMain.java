package com.carddemo.posttran;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point for the CBTRN01C port:
 * {@code java -cp cbtrn02c.jar com.carddemo.posttran.Cbtrn01cMain --input-dir DIR [--transact-file FILE|none]}.
 * Reads dailytran, custdata, cardxref, carddata and acctdata .txt from DIR and the transaction
 * master from FILE (default DIR/transact.txt); writes only SYSOUT. Exit codes match the COBOL
 * step: 0 normal end (unknown cards and accounts included), 16 abend (a file that cannot be
 * opened, as OPEN status 35).
 */
public final class Cbtrn01cMain {
    private Cbtrn01cMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path inputDir = null;
        String transactArg = null;
        boolean valid = args.length % 2 == 0;
        for (int i = 0; valid && i < args.length; i += 2) {
            switch (args[i]) {
                case "--input-dir" -> inputDir = Path.of(args[i + 1]);
                case "--transact-file" -> transactArg = args[i + 1];
                default -> valid = false;
            }
        }
        if (!valid || inputDir == null) {
            System.err.println("Usage: java -cp cbtrn02c.jar com.carddemo.posttran.Cbtrn01cMain"
                    + " --input-dir DIR [--transact-file FILE|none]");
            return 8;
        }
        Path transactFile = transactArg == null ? inputDir.resolve("transact.txt")
                : transactArg.equals("none") ? null : Path.of(transactArg);
        System.out.println("START OF EXECUTION OF PROGRAM CBTRN01C");
        try {
            // 0000-DALYTRAN-OPEN .. 0500-TRANFILE-OPEN, in the COBOL order.
            Path[] files = {inputDir.resolve(PostingFiles.DAILY_TRANSACTIONS), inputDir.resolve("custdata.txt"),
                inputDir.resolve(PostingFiles.CARD_XREF), inputDir.resolve("carddata.txt"),
                inputDir.resolve(PostingFiles.ACCOUNTS), transactFile};
            String[] names = {"DAILY TRANSACTION FILE", "CUSTOMER FILE", "CROSS REF FILE", "CARD FILE",
                "ACCOUNT FILE", "TRANSACTION FILE"};
            for (int i = 0; i < files.length; i++) {
                if (files[i] == null || !Files.isRegularFile(files[i])) {
                    System.out.println("ERROR OPENING " + names[i]);
                    System.out.println("FILE STATUS IS: NNNN0035");
                    return abend();
                }
            }
            TransactionVerification verification = new TransactionVerification(
                    PostingFiles.readCardXrefs(inputDir),
                    PostingFiles.readRecords(files[4], names[4], Account.LENGTH, 11),
                    System.out::println);
            verification.run(dailyRecords(files[0]));
            System.out.println("END OF EXECUTION OF PROGRAM CBTRN01C");
            return 0;
        } catch (IOException | RuntimeException e) {
            System.out.println(e.getMessage());
            return abend();
        }
    }

    /** DALYTRAN is fixed 350-byte records: line ends are dropped, as in run-cbtrn01c.sh. */
    private static List<String> dailyRecords(Path file) throws IOException {
        String data = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).replaceAll("[\r\n]", "");
        if (data.length() % DailyTransaction.LENGTH != 0) {
            throw new AbendException("ERROR READING DAILY TRANSACTION FILE");
        }
        List<String> records = new ArrayList<>();
        for (int i = 0; i < data.length(); i += DailyTransaction.LENGTH) {
            records.add(data.substring(i, i + DailyTransaction.LENGTH));
        }
        return records;
    }

    /** Z-ABEND-PROGRAM: CEE3ABD with abend code 999. */
    private static int abend() {
        System.out.println("ABENDING PROGRAM");
        System.err.println("CBTRN01C: ABEND U0999");
        return Main.ABEND;
    }
}
