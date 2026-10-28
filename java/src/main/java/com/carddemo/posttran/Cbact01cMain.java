package com.carddemo.posttran;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point for the CBACT01C port:
 * {@code java -cp cbtrn02c.jar com.carddemo.posttran.Cbact01cMain --input-dir DIR --output-dir DIR}.
 * Reads acctdata.txt; writes OUTFILE.dat, ARRYFILE.dat and VBRCFILE.dat. Exit codes match
 * the COBOL step: 0 normal end, 16 abend (e.g. no account file, as OPEN status 35).
 */
public final class Cbact01cMain {
    private static final String[] OUTPUTS = {"OUTFILE", "ARRYFILE", "VBRCFILE"};
    private static final String[] OPEN_NAMES = {"OUTFILE", "ARRAYFILE", "VBRC FILE"};

    private Cbact01cMain() {
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
            System.err.println("Usage: java -cp cbtrn02c.jar com.carddemo.posttran.Cbact01cMain"
                    + " --input-dir DIR --output-dir DIR");
            return 8;
        }
        if (!Files.isRegularFile(inputDir.resolve(PostingFiles.ACCOUNTS))) {
            // 0000-ACCTFILE-OPEN fails with status 35 (file not found); no output is opened.
            System.out.println("START OF EXECUTION OF PROGRAM CBACT01C");
            System.out.println("ERROR OPENING ACCTFILE");
            System.out.println("FILE STATUS IS: NNNN0035");
            return abend();
        }
        KeyedFile<Account> accounts;
        try {
            accounts = PostingFiles.readAccounts(inputDir);
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
            return abend();
        }
        OutputStream[] outputs = new OutputStream[OUTPUTS.length];
        try {
            for (int i = 0; i < OUTPUTS.length; i++) {
                try {
                    if (i == 0) {
                        Files.createDirectories(outputDir);
                    }
                    outputs[i] = Files.newOutputStream(outputDir.resolve(OUTPUTS[i] + ".dat"));
                } catch (IOException e) {
                    // 2000-OUTFILE-OPEN / 3000-ARRFILE-OPEN / 4000-VBRFILE-OPEN: GnuCOBOL status 30.
                    System.out.println("START OF EXECUTION OF PROGRAM CBACT01C");
                    System.out.println("ERROR OPENING " + OPEN_NAMES[i] + "30");
                    System.out.println("FILE STATUS IS: NNNN0030");
                    return abend();
                }
            }
            AccountExtract extract = new AccountExtract(System.out::println);
            extract.run(accounts.records());
            outputs[0].write(extract.outFile());
            outputs[1].write(extract.arryFile());
            outputs[2].write(extract.vbrcFile());
            extract.end();
            return 0;
        } catch (IOException e) {
            // 1350-1575 write failures: the COBOL displays the status and abends.
            System.out.println("ACCOUNT FILE WRITE STATUS IS:");
            System.err.println("CBACT01C: " + e);
            return abend();
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
            return abend();
        } finally {
            for (OutputStream output : outputs) {
                closeQuietly(output);
            }
        }
    }

    private static void closeQuietly(OutputStream output) {
        if (output != null) {
            try {
                output.close();
            } catch (IOException e) {
                System.err.println("CBACT01C: " + e.getMessage());
            }
        }
    }

    private static int abend() {
        System.out.println("ABENDING PROGRAM");
        System.err.println("CBACT01C: ABEND U0999");
        return Main.ABEND;
    }
}
