package com.carddemo.posttran;

import java.math.BigDecimal;

/**
 * One 300-byte account record (copybook CVACT01Y). Updates are written back into the
 * original record image, so fields the program does not change keep their exact bytes,
 * as with the COBOL READ INTO / REWRITE.
 */
final class Account {
    static final int LENGTH = 300;
    private static final int CURR_BAL = 12;
    private static final int CREDIT_LIMIT = 24;
    private static final int EXPIRATION_DATE = 58;
    private static final int CYCLE_CREDIT = 78;
    private static final int CYCLE_DEBIT = 90;
    private static final int GROUP_ID = 112;
    private static final int AMOUNT_LENGTH = 12;

    private final StringBuilder record;

    private Account(String record) {
        this.record = new StringBuilder(record);
    }

    static Account parse(String record) {
        Account account = new Account(record);
        account.currentBalance();
        account.creditLimit();
        account.cycleCredit();
        account.cycleDebit();
        return account;
    }

    String id() {
        return record.substring(0, 11);
    }

    BigDecimal currentBalance() {
        return amountAt(CURR_BAL);
    }

    BigDecimal creditLimit() {
        return amountAt(CREDIT_LIMIT);
    }

    String expirationDate() {
        return record.substring(EXPIRATION_DATE, EXPIRATION_DATE + 10);
    }

    BigDecimal cycleCredit() {
        return amountAt(CYCLE_CREDIT);
    }

    BigDecimal cycleDebit() {
        return amountAt(CYCLE_DEBIT);
    }

    /** 2800-UPDATE-ACCOUNT-REC. */
    void post(BigDecimal amount) {
        addTo(CURR_BAL, amount);
        addTo(amount.signum() >= 0 ? CYCLE_CREDIT : CYCLE_DEBIT, amount);
    }

    String groupId() {
        return record.substring(GROUP_ID, GROUP_ID + 10);
    }

    /** CBACT04C 1050-UPDATE-ACCOUNT: add the cycle's interest and reset the cycle totals. */
    void closeCycle(BigDecimal interest) {
        addTo(CURR_BAL, interest);
        String zero = ZonedDecimal.encode(BigDecimal.ZERO, 10, 2);
        record.replace(CYCLE_CREDIT, CYCLE_CREDIT + AMOUNT_LENGTH, zero);
        record.replace(CYCLE_DEBIT, CYCLE_DEBIT + AMOUNT_LENGTH, zero);
    }

    String toRecord() {
        return record.toString();
    }

    private BigDecimal amountAt(int offset) {
        return ZonedDecimal.decode(record.substring(offset, offset + AMOUNT_LENGTH), 2);
    }

    private void addTo(int offset, BigDecimal amount) {
        String updated = ZonedDecimal.encode(amountAt(offset).add(amount), 10, 2);
        record.replace(offset, offset + AMOUNT_LENGTH, updated);
    }
}
