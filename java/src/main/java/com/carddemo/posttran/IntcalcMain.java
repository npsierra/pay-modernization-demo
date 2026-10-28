package com.carddemo.posttran;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Command-line entry point for the CBACT04C port:
 * {@code java -cp cbtrn02c.jar com.carddemo.posttran.IntcalcMain --input-dir DIR --output-dir DIR [--parm DATE]}.
 * Reads tcatbal.txt, acctdata.txt, cardxref.txt and discgrp.txt; writes ACCTDATA.txt and
 * SYSTRAN.txt. Exit codes match the COBOL step: 0 normal end, 16 abend.
 */
public final class IntcalcMain {
    static final String DEFAULT_PARM = "2022071800";

    private IntcalcMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemDefaultZone()));
    }

    static int run(String[] args, Clock clock) {
        Path inputDir = null;
        Path outputDir = null;
        String parm = DEFAULT_PARM;
        boolean valid = args.length % 2 == 0;
        for (int i = 0; valid && i < args.length; i += 2) {
            switch (args[i]) {
                case "--input-dir" -> inputDir = Path.of(args[i + 1]);
                case "--output-dir" -> outputDir = Path.of(args[i + 1]);
                case "--parm" -> parm = args[i + 1];
                default -> valid = false;
            }
        }
        if (!valid || inputDir == null || outputDir == null) {
            System.err.println("Usage: java -cp cbtrn02c.jar com.carddemo.posttran.IntcalcMain"
                    + " --input-dir DIR --output-dir DIR [--parm YYYYMMDDNN]");
            return 8;
        }
        try {
            Files.createDirectories(outputDir);
            InterestCalculation calculation = new InterestCalculation(
                    PostingFiles.readAccounts(inputDir),
                    PostingFiles.readCardXrefs(inputDir),
                    PostingFiles.readDisclosureGroups(inputDir),
                    parm,
                    Main.processingTimestamp(clock),
                    System.out::println);
            calculation.run(PostingFiles.readCategoryBalances(inputDir).records());
            write(outputDir.resolve(PostingFiles.ACCOUNTS_OUT),
                    calculation.accounts().records().stream().map(Account::toRecord).toList());
            write(outputDir.resolve("SYSTRAN.txt"), calculation.transactionRecords());
            return 0;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.out.println("ABENDING PROGRAM");
            System.err.println("CBACT04C: ABEND U0999");
            return Main.ABEND;
        }
    }

    private static void write(Path file, java.util.List<String> records) {
        try {
            Files.write(file, records, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
