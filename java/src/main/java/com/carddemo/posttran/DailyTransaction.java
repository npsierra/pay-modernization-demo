package com.carddemo.posttran;

import java.math.BigDecimal;

/** One 350-byte DALYTRAN record (copybook CVTRA06Y). */
record DailyTransaction(String record) {
    static final int LENGTH = 350;
    private static final int PROC_TS_START = 304;

    DailyTransaction {
        if (record.length() != LENGTH) {
            throw new IllegalArgumentException(
                    "Daily transaction must be " + LENGTH + " bytes, got " + record.length());
        }
    }

    String id() {
        return record.substring(0, 16);
    }

    String typeCode() {
        return record.substring(16, 18);
    }

    String categoryCode() {
        return record.substring(18, 22);
    }

    BigDecimal amount() {
        return ZonedDecimal.decode(record.substring(132, 143), 2);
    }

    String cardNumber() {
        return record.substring(262, 278);
    }

    String originTimestamp() {
        return record.substring(278, PROC_TS_START);
    }

    /**
     * TRAN-RECORD as built by 2000-POST-TRANSACTION: the DALYTRAN fields up to TRAN-ORIG-TS,
     * then the processing timestamp and a blank FILLER.
     */
    String toTransactionRecord(String processingTimestamp) {
        return record.substring(0, PROC_TS_START) + processingTimestamp + " ".repeat(20);
    }
}
