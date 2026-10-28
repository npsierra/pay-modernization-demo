package com.carddemo.posttran;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Command-line entry point for the CBTRN03C port (TRANREPT job):
 * {@code java -cp cbtrn02c.jar com.carddemo.posttran.Cbtrn03cMain --input-dir DIR --output-dir DIR}.
 * Reads transact.txt (filtered and sorted like STEP05R) or tranfile.txt (used as is),
 * cardxref.txt, trantype.txt, trancatg.txt and dateparm.txt; writes TRANREPT.txt.
 * Exit codes match the COBOL step: 0 normal end, 16 abend (U0999).
 */
public final class Cbtrn03cMain {
    static final String TRANSACTIONS = "transact.txt";
    static final String SORTED_TRANSACTIONS = "tranfile.txt";
    static final String DATE_PARMS = "dateparm.txt";
    static final String REPORT = "TRANREPT.txt";

    private Cbtrn03cMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path inputDir = null;
        Path outputDir = null;
        boolean valid = args.length % 2 == 0;
        for (int i = 0; valid && i < args.length; i += 2) {
            switch (args[i]) {
                case "--input-dir" -> inputDir = Path.of(args[i + 1]);
                case "--output-dir" -> outputDir = Path.of(args[i + 1]);
                default -> valid = false;
            }
        }
        if (!valid || inputDir == null || outputDir == null) {
            System.err.println("Usage: java -cp cbtrn02c.jar com.carddemo.posttran.Cbtrn03cMain"
                    + " --input-dir DIR --output-dir DIR");
            return 8;
        }
        Path dateParmFile = inputDir.resolve(DATE_PARMS);
        List<String> dateParms = Files.isRegularFile(dateParmFile)
                ? fixed(PostingFiles.lines(dateParmFile), 80) : null;
        List<String> transactions = readTranFile(inputDir, dateParms);

        System.out.println("START OF EXECUTION OF PROGRAM CBTRN03C");
        // 0000-0500 opens, in the COBOL order; a missing file is OPEN status 35.
        if (transactions == null) {
            return openFailed("TRANFILE");
        }
        Path reportFile = outputDir.resolve(REPORT);
        TransactionReport report = null;
        try {
            Files.createDirectories(outputDir);
            Files.write(reportFile, new byte[0]);
            if (!Files.isRegularFile(inputDir.resolve(PostingFiles.CARD_XREF))) {
                return openFailed("CROSS REF FILE");
            }
            if (!Files.isRegularFile(inputDir.resolve(PostingFiles.TRANSACTION_TYPES))) {
                return openFailed("TRANSACTION TYPE FILE");
            }
            if (!Files.isRegularFile(inputDir.resolve(PostingFiles.TRANSACTION_CATEGORIES))) {
                return openFailed("TRANSACTION CATG FILE");
            }
            if (dateParms == null) {
                return openFailed("DATE PARM FILE");
            }
            report = new TransactionReport(PostingFiles.readCardXrefs(inputDir),
                    PostingFiles.readTransactionTypes(inputDir),
                    PostingFiles.readTransactionCategories(inputDir), System.out::println);
            report.run(dateParms.isEmpty() ? null : dateParms.get(0), transactions);
            writeReport(reportFile, report);
            System.out.println("END OF EXECUTION OF PROGRAM CBTRN03C");
            return 0;
        } catch (IOException e) {
            System.out.println("ERROR WRITING REPTFILE: " + e.getMessage());
            return abend();
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
            if (report != null) {
                try {
                    writeReport(reportFile, report);
                } catch (IOException ignored) {
                    // The abend is reported either way.
                }
            }
            return abend();
        }
    }

    /**
     * TRANREPT STEP05R (SORT): keeps transactions whose processing date (cols 305-314) is in
     * the DATEPARM range and sorts them by card number (cols 263-278), stable. Without a
     * DATEPARM nothing is filtered. Returns null when there is no transaction file.
     */
    static List<String> readTranFile(Path inputDir, List<String> dateParms) {
        Path sorted = inputDir.resolve(SORTED_TRANSACTIONS);
        if (Files.isRegularFile(sorted)) {
            return fixed(PostingFiles.lines(sorted), TransactionReport.Transaction.LENGTH);
        }
        Path unload = inputDir.resolve(TRANSACTIONS);
        if (!Files.isRegularFile(unload)) {
            return null;
        }
        List<String> records = fixed(PostingFiles.lines(unload), TransactionReport.Transaction.LENGTH);
        if (dateParms != null) {
            String start = dateParms.isEmpty() ? "" : dateParms.get(0).substring(0, 10);
            String end = dateParms.isEmpty() ? "" : dateParms.get(0).substring(11, 21);
            records.removeIf(r -> {
                String date = TransactionReport.Transaction.processingDate(r);
                return date.compareTo(start) < 0 || date.compareTo(end) > 0;
            });
        }
        records.sort(Comparator.comparing(TransactionReport.Transaction::cardNumber));
        return records;
    }

    /** Fixed-length records: each line padded with spaces or cut to the record length. */
    private static List<String> fixed(List<String> lines, int length) {
        List<String> records = new ArrayList<>();
        for (String line : lines) {
            records.add(TransactionReport.pad(line, length));
        }
        return records;
    }

    private static void writeReport(Path reportFile, TransactionReport report) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String record : report.records()) {
            text.append(record).append('\n');
        }
        Files.writeString(reportFile, text, StandardCharsets.US_ASCII);
    }

    private static int openFailed(String file) {
        System.out.println("ERROR OPENING " + file);
        System.out.println("FILE STATUS IS: NNNN0035");
        return abend();
    }

    private static int abend() {
        System.out.println("ABENDING PROGRAM");
        System.err.println("CBTRN03C: ABEND U0999");
        return Main.ABEND;
    }
}
