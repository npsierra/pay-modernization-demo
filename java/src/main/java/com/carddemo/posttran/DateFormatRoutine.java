package com.carddemo.posttran;

import java.util.Locale;

/**
 * Port of the assembler date routine COBDATFT (app/asm/COBDATFT.asm, stand-in
 * gnucobol/COBDATFT.cbl). Works on the 80-byte CODATECN-REC parameter in place and changes
 * only the bytes the assembler moves; the caller keeps the area between calls, as the COBOL
 * WORKING-STORAGE item does.
 */
final class DateFormatRoutine {
    static final int LENGTH = 80;
    static final int IN_TYPE = 0;
    static final int IN_DATE = 1;
    static final int OUT_TYPE = 21;
    static final int OUT_DATE = 22;
    static final int ERROR_MSG = 42;
    private static final int DATE_LENGTH = 20;

    private DateFormatRoutine() {
    }

    /** New CODATECN-REC as GnuCOBOL initializes WORKING-STORAGE without VALUE: all spaces. */
    static char[] newRecord() {
        return " ".repeat(LENGTH).toCharArray();
    }

    static void call(char[] rec) {
        char inType = rec[IN_TYPE];
        char outType = rec[OUT_TYPE];
        if (inType == '1') {
            if (rec[IN_DATE + 4] == '-' || outType == '2') {
                invalid(rec);
                return;
            }
            copy(rec, IN_DATE, OUT_DATE, 4);
            rec[OUT_DATE + 4] = '-';
            copy(rec, IN_DATE + 4, OUT_DATE + 5, 2);
            rec[OUT_DATE + 7] = '-';
            copy(rec, IN_DATE + 6, OUT_DATE + 8, 2);
        } else if (inType == '2') {
            if (outType == '1') {
                invalid(rec);
                return;
            }
            copy(rec, IN_DATE, OUT_DATE, 4);
            copy(rec, IN_DATE + 5, OUT_DATE + 4, 2);
            copy(rec, IN_DATE + 8, OUT_DATE + 6, 2);
        } else {
            invalid(rec);
        }
    }

    /** MOVE of a shorter field into CODATECN-INP-DATE: left-justified, space padded. */
    static void setInputDate(char[] rec, String date) {
        String padded = String.format(Locale.ROOT, "%-" + DATE_LENGTH + "s", date);
        padded.getChars(0, DATE_LENGTH, rec, IN_DATE);
    }

    static String outputDate(char[] rec) {
        return new String(rec, OUT_DATE, DATE_LENGTH);
    }

    private static void copy(char[] rec, int from, int to, int length) {
        System.arraycopy(rec, from, rec, to, length);
    }

    private static void invalid(char[] rec) {
        String.format(Locale.ROOT, "%-38s", "INVALID INPUT").getChars(0, 38, rec, ERROR_MSG);
    }
}
