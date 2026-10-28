package com.carddemo.posttran;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Port of CBACT01C (READACCT STEP05): reads every account and writes three extracts. Output
 * records are built byte for byte as GnuCOBOL writes them: OUTFILE (fixed 107 bytes),
 * ARRYFILE (fixed 110 bytes), both with COMP-3 fields, and VBRCFILE (variable length, each
 * record preceded by a 2-byte big-endian length and 2 zero bytes).
 */
final class AccountExtract {
    static final int OUT_LENGTH = 107;
    static final int ARRY_LENGTH = 110;
    private static final int AMOUNT = 12;
    private static final int PACKED = PackedDecimal.length(10, 2);
    private static final int ARRY_SLOT = AMOUNT + PACKED;
    private static final int OUT_CYC_DEBIT = 90;
    private static final String DASHES = "-------------------------------------------------";

    private final Consumer<String> sysout;
    private final ByteArrayOutputStream outFile = new ByteArrayOutputStream();
    private final ByteArrayOutputStream arryFile = new ByteArrayOutputStream();
    private final ByteArrayOutputStream vbrcFile = new ByteArrayOutputStream();
    /** OUT-ACCT-REC: the FD record area. It is never cleared, so fields not moved keep old bytes. */
    private final byte[] outRecord = new byte[OUT_LENGTH];
    /** CODATECN-REC: WORKING-STORAGE, kept between COBDATFT calls. */
    private final char[] codatecn = DateFormatRoutine.newRecord();
    private String vbrcRec1;
    private String vbrcRec2;

    AccountExtract(Consumer<String> sysout) {
        this.sysout = sysout;
    }

    void run(Iterable<Account> accounts) {
        sysout.accept("START OF EXECUTION OF PROGRAM CBACT01C");
        for (Account account : accounts) {
            sysout.accept(getNext(account));
        }
    }

    /** Displayed only once every output is written, as the COBOL abends at the failing WRITE. */
    void end() {
        sysout.accept("END OF EXECUTION OF PROGRAM CBACT01C");
    }

    /**
     * 1000-ACCTFILE-GET-NEXT: the steps performed for each account read. Returns ACCOUNT-RECORD
     * as it is afterwards, which the main loop displays.
     */
    private String getNext(Account account) {
        String rec = displayAccount(account.toRecord());
        populateOutRecord(rec, account);
        outFile.writeBytes(outRecord);
        arryFile.writeBytes(arrayRecord(rec));
        populateVbrcRecords(rec);
        writeVariable(vbrcRec1);
        writeVariable(vbrcRec2);
        return rec;
    }

    /**
     * 1100-DISPLAY-ACCT-RECORD. GnuCOBOL rewrites the sign of each signed field it displays,
     * so afterwards a plain last digit in ACCOUNT-RECORD has become a positive overpunch; the
     * returned record carries that change into the rest of the program.
     */
    private String displayAccount(String rec) {
        sysout.accept("ACCT-ID                 :" + rec.substring(0, 11));
        sysout.accept("ACCT-ACTIVE-STATUS      :" + rec.substring(11, 12));
        sysout.accept("ACCT-CURR-BAL           :" + ZonedDecimal.display(rec.substring(12, 24), 2));
        sysout.accept("ACCT-CREDIT-LIMIT       :" + ZonedDecimal.display(rec.substring(24, 36), 2));
        sysout.accept("ACCT-CASH-CREDIT-LIMIT  :" + ZonedDecimal.display(rec.substring(36, 48), 2));
        sysout.accept("ACCT-OPEN-DATE          :" + rec.substring(48, 58));
        sysout.accept("ACCT-EXPIRAION-DATE     :" + rec.substring(58, 68));
        sysout.accept("ACCT-REISSUE-DATE       :" + rec.substring(68, 78));
        sysout.accept("ACCT-CURR-CYC-CREDIT    :" + ZonedDecimal.display(rec.substring(78, 90), 2));
        sysout.accept("ACCT-CURR-CYC-DEBIT     :" + ZonedDecimal.display(rec.substring(90, 102), 2));
        sysout.accept("ACCT-GROUP-ID           :" + rec.substring(112, 122));
        sysout.accept(DASHES);
        StringBuilder after = new StringBuilder(rec);
        for (int offset : new int[] {12, 24, 36, 78, 90}) {
            after.replace(offset, offset + AMOUNT, ZonedDecimal.move(rec.substring(offset, offset + AMOUNT)));
        }
        return after.toString();
    }

    /**
     * 1300-POPUL-ACCT-RECORD. The reissue date goes through COBDATFT (YYYY-MM-DD to
     * YYYYMMDD; the last 2 of the 10 bytes are whatever CODATECN-0UT-DATE held). The cycle
     * debit is only set, to 2525.00, when the account's cycle debit is zero; otherwise the
     * previous record's bytes stay (binary zeros before the first one).
     */
    private void populateOutRecord(String rec, Account account) {
        put(outRecord, 0, rec.substring(0, 12));
        put(outRecord, 12, ZonedDecimal.move(rec.substring(12, 24)));
        put(outRecord, 24, ZonedDecimal.move(rec.substring(24, 36)));
        put(outRecord, 36, ZonedDecimal.move(rec.substring(36, 48)));
        put(outRecord, 48, rec.substring(48, 68));
        DateFormatRoutine.setInputDate(codatecn, rec.substring(68, 78));
        codatecn[DateFormatRoutine.IN_TYPE] = '2';
        codatecn[DateFormatRoutine.OUT_TYPE] = '2';
        DateFormatRoutine.call(codatecn);
        put(outRecord, 68, DateFormatRoutine.outputDate(codatecn).substring(0, 10));
        put(outRecord, 78, ZonedDecimal.move(rec.substring(78, 90)));
        if (account.cycleDebit().signum() == 0) {
            System.arraycopy(PackedDecimal.encode(new BigDecimal("2525.00"), 10, 2), 0, outRecord, OUT_CYC_DEBIT,
                    PACKED);
        }
        put(outRecord, 97, rec.substring(112, 122));
    }

    /**
     * 1400-POPUL-ARRAY-RECORD, after INITIALIZE ARR-ARRAY-REC (GnuCOBOL: zoned zeros without a
     * sign, packed zeros, spaces). Slots 1-2 get the balance with fixed debits 1005.00 and
     * 1525.00, slot 3 fixed values -1025.00 / -2500.00, slots 4-5 stay zero.
     */
    private byte[] arrayRecord(String rec) {
        byte[] arr = new byte[ARRY_LENGTH];
        put(arr, 0, rec.substring(0, 11));
        byte[] packedZero = PackedDecimal.encode(BigDecimal.ZERO, 10, 2);
        for (int slot = 0; slot < 5; slot++) {
            put(arr, 11 + slot * ARRY_SLOT, "0".repeat(AMOUNT));
            System.arraycopy(packedZero, 0, arr, 11 + slot * ARRY_SLOT + AMOUNT, PACKED);
        }
        Arrays.fill(arr, ARRY_LENGTH - 4, ARRY_LENGTH, (byte) ' ');
        String balance = ZonedDecimal.move(rec.substring(12, 24));
        setSlot(arr, 0, balance, "1005.00");
        setSlot(arr, 1, balance, "1525.00");
        setSlot(arr, 2, ZonedDecimal.encode(new BigDecimal("-1025.00"), 10, 2), "-2500.00");
        return arr;
    }

    /** 1500-POPUL-VBRC-RECORD (after INITIALIZE VBRC-REC1). */
    private void populateVbrcRecords(String rec) {
        vbrcRec1 = rec.substring(0, 12);
        vbrcRec2 = rec.substring(0, 11) + ZonedDecimal.move(rec.substring(12, 24))
                + ZonedDecimal.move(rec.substring(24, 36)) + rec.substring(68, 72);
        sysout.accept("VBRC-REC1:" + vbrcRec1);
        sysout.accept("VBRC-REC2:" + vbrcRec2);
    }

    /** 1550-WRITE-VB1-RECORD / 1575-WRITE-VB2-RECORD: 12- and 39-byte variable-length records. */
    private void writeVariable(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
        vbrcFile.write(bytes.length >> 8);
        vbrcFile.write(bytes.length & 0xFF);
        vbrcFile.write(0);
        vbrcFile.write(0);
        vbrcFile.writeBytes(bytes);
    }

    byte[] outFile() {
        return outFile.toByteArray();
    }

    byte[] arryFile() {
        return arryFile.toByteArray();
    }

    byte[] vbrcFile() {
        return vbrcFile.toByteArray();
    }

    private static void setSlot(byte[] arr, int slot, String balance, String debit) {
        put(arr, 11 + slot * ARRY_SLOT, balance);
        System.arraycopy(PackedDecimal.encode(new BigDecimal(debit), 10, 2), 0, arr, 11 + slot * ARRY_SLOT + AMOUNT,
                PACKED);
    }

    private static void put(byte[] target, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
}
