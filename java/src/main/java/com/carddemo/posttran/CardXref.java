package com.carddemo.posttran;

/** One 50-byte card cross-reference record (copybook CVACT03Y). */
record CardXref(String cardNumber, String customerId, String accountId) {
    static final int LENGTH = 50;

    static CardXref parse(String record) {
        return new CardXref(record.substring(0, 16), record.substring(16, 25), record.substring(25, 36));
    }
}
