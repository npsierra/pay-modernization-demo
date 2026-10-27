package com.carddemo.posttran;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * Command-line entry point: {@code java -jar cbtrn02c.jar --input-dir DIR --output-dir DIR}.
 * Exit codes match the COBOL step: 0 all posted, 4 some rejected, 16 abend.
 */
public final class Main {
    static final int ABEND = 16;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemDefaultZone()));
    }

    static int run(String[] args, Clock clock) {
        Path inputDir = null;
        Path outputDir = null;
        boolean valid = args.length == 4;
        for (int i = 0; valid && i < args.length; i += 2) {
            switch (args[i]) {
                case "--input-dir" -> inputDir = Path.of(args[i + 1]);
                case "--output-dir" -> outputDir = Path.of(args[i + 1]);
                default -> valid = false;
            }
        }
        if (!valid || inputDir == null || outputDir == null) {
            System.err.println("Usage: java -jar cbtrn02c.jar --input-dir DIR --output-dir DIR");
            return 8;
        }
        try {
            Files.createDirectories(outputDir);
            DailyTransactionPosting posting = new DailyTransactionPosting(
                    PostingFiles.readCardXrefs(inputDir),
                    PostingFiles.readAccounts(inputDir),
                    PostingFiles.readCategoryBalances(inputDir),
                    processingTimestamp(clock),
                    System.out::println);
            List<DailyTransaction> daily = PostingFiles.readDailyTransactions(inputDir);
            DailyTransactionPosting.Result result = posting.run(daily);
            PostingFiles.writeOutputs(posting, outputDir);
            return result.returnCode();
        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.out.println("ABENDING PROGRAM");
            System.err.println("CBTRN02C: ABEND U0999");
            return ABEND;
        }
    }

    /** Z-GET-DB2-FORMAT-TIMESTAMP: local time as YYYY-MM-DD-HH.MM.SS.hh0000. */
    static Supplier<String> processingTimestamp(Clock clock) {
        return () -> {
            LocalDateTime now = LocalDateTime.now(clock);
            return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%02d0000", now.getYear(), now.getMonthValue(),
                    now.getDayOfMonth(), now.getHour(), now.getMinute(), now.getSecond(),
                    now.getNano() / 10_000_000);
        };
    }
}
