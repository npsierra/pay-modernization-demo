package com.carddemo.posttran;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Port of CBACT02C (READCARD STEP05): reads the card file (CARDFILE, a KSDS of 150-byte
 * CARD-RECORDs, copybook CVACT02Y) in key order and displays every record. Nothing is
 * validated or written. Any file status other than '00' (or '10' at end of file) displays
 * the status and abends, as in the COBOL.
 */
final class CardFileListing {
    static final int RECORD_LENGTH = 150;
    static final String FILE_NOT_FOUND = "35";
    static final String PERMISSION_DENIED = "37";

    private final Consumer<String> display;
    private final String openStatus;
    private final List<String> cardFile;
    private int nextRecord;

    // WORKING-STORAGE, with the COBOL initial values.
    private String cardRecord = " ".repeat(RECORD_LENGTH);
    private String cardfileStatus = "  ";
    private String ioStatus = "  ";
    private int applResult;
    private char endOfFile = 'N';

    /**
     * @param cards      the card records in key order (as the KSDS returns them)
     * @param openStatus the file status the OPEN returns: "00", or e.g. "35" when CARDFILE
     *                   doesn't exist
     */
    CardFileListing(List<String> cards, String openStatus, Consumer<String> display) {
        this.cardFile = cards;
        this.openStatus = openStatus;
        this.display = display;
    }

    /** PROCEDURE DIVISION main line. Throws {@link AbendException} after 9999-ABEND-PROGRAM. */
    void run() {
        display.accept("START OF EXECUTION OF PROGRAM CBACT02C");
        cardfileOpen();
        while (endOfFile != 'Y') {
            if (endOfFile == 'N') {
                cardfileGetNext();
                if (endOfFile == 'N') {
                    display.accept(cardRecord);
                }
            }
        }
        cardfileClose();
        display.accept("END OF EXECUTION OF PROGRAM CBACT02C");
    }

    /** 1000-CARDFILE-GET-NEXT: '00' = record read, '10' = end of file, anything else abends. */
    void cardfileGetNext() {
        if (nextRecord < cardFile.size()) {
            cardRecord = cardFile.get(nextRecord++);
            cardfileStatus = "00";
        } else {
            cardfileStatus = "10";
        }
        if (cardfileStatus.equals("00")) {
            applResult = 0;
        } else if (cardfileStatus.equals("10")) {
            applResult = 16;
        } else {
            applResult = 12;
        }
        if (applResult == 0) {
            return;
        }
        if (applResult == 16) {
            endOfFile = 'Y';
        } else {
            display.accept("ERROR READING CARDFILE");
            ioStatus = cardfileStatus;
            displayIoStatus();
            abendProgram();
        }
    }

    /** 0000-CARDFILE-OPEN: any status other than '00' abends. */
    void cardfileOpen() {
        applResult = 8;
        cardfileStatus = openStatus;
        applResult = cardfileStatus.equals("00") ? 0 : 12;
        if (applResult != 0) {
            display.accept("ERROR OPENING CARDFILE");
            ioStatus = cardfileStatus;
            displayIoStatus();
            abendProgram();
        }
    }

    /** 9000-CARDFILE-CLOSE: any status other than '00' abends. */
    void cardfileClose() {
        applResult = 8;
        cardfileStatus = "00";
        applResult = cardfileStatus.equals("00") ? 0 : 12;
        if (applResult != 0) {
            display.accept("ERROR CLOSING CARDFILE");
            ioStatus = cardfileStatus;
            displayIoStatus();
            abendProgram();
        }
    }

    /** 9999-ABEND-PROGRAM: CALL 'CEE3ABD' with ABCODE 999 (exit code 16 off-mainframe). */
    void abendProgram() {
        display.accept("ABENDING PROGRAM");
        throw new AbendException("CBACT02C: ABEND U0999");
    }

    /**
     * 9910-DISPLAY-IO-STATUS: a numeric status prints as {@code 00nn}. A non-numeric status or
     * one starting with '9' prints its first byte followed by the second byte's code as 3
     * digits (TWO-BYTES-BINARY), e.g. "9" + 'A' gives {@code 9065}.
     */
    void displayIoStatus() {
        display.accept("FILE STATUS IS: NNNN" + formatIoStatus(ioStatus));
    }

    static String formatIoStatus(String status) {
        char stat1 = status.charAt(0);
        char stat2 = status.charAt(1);
        boolean numeric = Character.isDigit(stat1) && stat1 < 128 && Character.isDigit(stat2) && stat2 < 128;
        if (!numeric || stat1 == '9') {
            return stat1 + String.format(Locale.ROOT, "%03d", stat2 & 0xFF);
        }
        return "00" + status;
    }
}
