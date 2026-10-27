package com.carddemo.posttran;

import java.math.BigDecimal;

/** One 50-byte transaction category balance record (copybook CVTRA01Y). */
final class CategoryBalance {
    static final int LENGTH = 50;
    static final int KEY_LENGTH = 17;
    private static final int BALANCE_LENGTH = 11;

    private final StringBuilder record;

    private CategoryBalance(String record) {
        this.record = new StringBuilder(record);
    }

    static CategoryBalance parse(String record) {
        CategoryBalance balance = new CategoryBalance(record);
        balance.balance();
        return balance;
    }

    /** 2700-A-CREATE-TCATBAL-REC: key and zero balance; FILLER comes from the caller. */
    static CategoryBalance create(String key, String filler) {
        return new CategoryBalance(key + ZonedDecimal.encode(BigDecimal.ZERO, 9, 2) + filler);
    }

    static String key(String accountId, String typeCode, String categoryCode) {
        return accountId + typeCode + categoryCode;
    }

    String key() {
        return record.substring(0, KEY_LENGTH);
    }

    BigDecimal balance() {
        return ZonedDecimal.decode(record.substring(KEY_LENGTH, KEY_LENGTH + BALANCE_LENGTH), 2);
    }

    String filler() {
        return record.substring(KEY_LENGTH + BALANCE_LENGTH);
    }

    void add(BigDecimal amount) {
        record.replace(KEY_LENGTH, KEY_LENGTH + BALANCE_LENGTH,
                ZonedDecimal.encode(balance().add(amount), 9, 2));
    }

    String toRecord() {
        return record.toString();
    }
}
