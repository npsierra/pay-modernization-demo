package com.carddemo.posttran;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Port of CBACT02C (READCARD STEP05): reads the card file in key order and displays each
 * 150-byte card record as stored. The file status decides the outcome as in the COBOL:
 * "00" carries on, "10" on a read ends the listing, anything else abends with U0999.
 */
final class CardFileListing {
    static final int RECORD_LENGTH = 150;

    /** CARDFILE as the program sees it: the status its OPEN returns, then its records in key order. */
    record CardFile(String openStatus, List<String> records) {
    }

    private final Consumer<String> sysout;
    private Iterator<String> cursor;
    private String cardfileStatus = "00";
    /** CARD-RECORD (CVACT02Y), the READ ... INTO target. */
    private String cardRecord = " ".repeat(RECORD_LENGTH);
    private boolean endOfFile;

    CardFileListing(Consumer<String> sysout) {
        this.sysout = sysout;
    }

    /** PROCEDURE DIVISION main line. Returns the step's return code: 0, or 16 after an abend. */
    int run(CardFile file) {
        sysout.accept("START OF EXECUTION OF PROGRAM CBACT02C");
        if (!cardfileOpen(file)) {
            return abendProgram();
        }
        while (!endOfFile) {
            if (!cardfileGetNext()) {
                return abendProgram();
            }
            if (!endOfFile) {
                sysout.accept(cardRecord);
            }
        }
        if (!cardfileClose()) {
            return abendProgram();
        }
        sysout.accept("END OF EXECUTION OF PROGRAM CBACT02C");
        return 0;
    }

    /** 0000-CARDFILE-OPEN: any status but "00" is an error. Returns false when the program abends. */
    private boolean cardfileOpen(CardFile file) {
        cardfileStatus = file.openStatus();
        if ("00".equals(cardfileStatus)) {
            cursor = file.records().iterator();
            return true;
        }
        sysout.accept("ERROR OPENING CARDFILE");
        displayIoStatus(cardfileStatus);
        return false;
    }

    /**
     * 1000-CARDFILE-GET-NEXT: status "00" moves the next record into CARD-RECORD, "10" (end of
     * file) sets END-OF-FILE, anything else is an error. Returns false when the program abends.
     */
    private boolean cardfileGetNext() {
        if (cursor.hasNext()) {
            cardRecord = cursor.next();
            cardfileStatus = "00";
        } else {
            cardfileStatus = "10";
        }
        if ("00".equals(cardfileStatus)) {
            return true;
        }
        if ("10".equals(cardfileStatus)) {
            endOfFile = true;
            return true;
        }
        sysout.accept("ERROR READING CARDFILE");
        displayIoStatus(cardfileStatus);
        return false;
    }

    /** 9000-CARDFILE-CLOSE: closing the in-memory file always returns "00". */
    private boolean cardfileClose() {
        cardfileStatus = "00";
        if ("00".equals(cardfileStatus)) {
            return true;
        }
        sysout.accept("ERROR CLOSING CARDFILE");
        displayIoStatus(cardfileStatus);
        return false;
    }

    /** 9910-DISPLAY-IO-STATUS. */
    private void displayIoStatus(String status) {
        sysout.accept("FILE STATUS IS: NNNN" + ioStatus04(status));
    }

    /**
     * IO-STATUS-04 as 9910-DISPLAY-IO-STATUS builds it: "00" + the status when it is numeric
     * and does not start with 9; otherwise the first byte followed by the second byte's code
     * as three digits (TWO-BYTES-BINARY moved to PIC 999), e.g. "9d" becomes "9100".
     */
    static String ioStatus04(String status) {
        boolean numeric = status.chars().allMatch(c -> c >= '0' && c <= '9');
        if (!numeric || status.charAt(0) == '9') {
            return status.charAt(0) + String.format(Locale.ROOT, "%03d", status.charAt(1) & 0xFF);
        }
        return "00" + status;
    }

    /** 9999-ABEND-PROGRAM: CEE3ABD with abend code 999 ends the step with return code 16. */
    private int abendProgram() {
        sysout.accept("ABENDING PROGRAM");
        System.err.println("CBACT02C: ABEND U0999");
        return Main.ABEND;
    }
}
