package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TransactionValidatorTest {
    private static final String CARD = "4111111111111111";
    private static final String ACCOUNT_ID = "00000000042";

    @Test
    void acceptsWithinLimitBeforeExpiration() {
        assertNull(validate(account("00000100000{", "00000050000{", "00000000000{", "2030-01-01"),
                purchase("0000005000{", "2025-01-01")).reason());
    }

    @Test
    void acceptsExactlyAtLimit() {
        // credit 500.00 + purchase 500.00 = limit 1000.00
        assertNull(validate(account("00000100000{", "00000050000{", "00000000000{", "2030-01-01"),
                purchase("0000050000{", "2025-01-01")).reason());
    }

    @Test
    void rejectsOverLimit() {
        assertEquals(RejectReason.OVERLIMIT,
                validate(account("00000100000{", "00000050000{", "00000000000{", "2030-01-01"),
                        purchase("0000050001A", "2025-01-01")).reason());
    }

    @Test
    void returnsCountAgainstTheLimitAsInTheOriginal() {
        // credit 500.00, returns (debit) -300.00: 500.00 - (-300.00) + 250.00 = 1050.00 > 1000.00
        assertEquals(RejectReason.OVERLIMIT,
                validate(account("00000100000{", "00000050000{", "00000030000}", "2030-01-01"),
                        purchase("0000025000{", "2025-01-01")).reason());
    }

    @Test
    void rejectsAfterExpiration() {
        assertEquals(RejectReason.EXPIRED_ACCOUNT,
                validate(account("00000100000{", "00000000000{", "00000000000{", "2024-12-31"),
                        purchase("0000001000{", "2025-01-01")).reason());
    }

    @Test
    void expirationOverridesOverLimit() {
        assertEquals(RejectReason.EXPIRED_ACCOUNT,
                validate(account("00000001000{", "00000000000{", "00000000000{", "2024-12-31"),
                        purchase("0000050000{", "2025-01-01")).reason());
    }

    @Test
    void rejectsUnknownCard() {
        TransactionValidator validator = new TransactionValidator(
                new KeyedFile<>("XREF", CardXref::cardNumber), new KeyedFile<>("ACCT", Account::id));
        assertEquals(RejectReason.INVALID_CARD, validator.validate(purchase("0000001000{", "2025-01-01")).reason());
    }

    @Test
    void rejectsMissingAccount() {
        KeyedFile<CardXref> xrefs = new KeyedFile<>("XREF", CardXref::cardNumber);
        xrefs.write(new CardXref(CARD, "000000001", ACCOUNT_ID));
        TransactionValidator validator = new TransactionValidator(xrefs, new KeyedFile<>("ACCT", Account::id));
        assertEquals(RejectReason.ACCOUNT_NOT_FOUND,
                validator.validate(purchase("0000001000{", "2025-01-01")).reason());
    }

    private static TransactionValidator.Validation validate(Account account, DailyTransaction transaction) {
        KeyedFile<CardXref> xrefs = new KeyedFile<>("XREF", CardXref::cardNumber);
        xrefs.write(new CardXref(CARD, "000000001", ACCOUNT_ID));
        KeyedFile<Account> accounts = new KeyedFile<>("ACCT", Account::id);
        accounts.write(account);
        return new TransactionValidator(xrefs, accounts).validate(transaction);
    }

    static Account account(String limit, String cycleCredit, String cycleDebit, String expiration) {
        String record = ACCOUNT_ID + "Y" + "00000000000{" + limit + "00000000000{"
                + "2020-01-01" + expiration + "2020-01-01" + cycleCredit + cycleDebit;
        return Account.parse(String.format("%-300s", record));
    }

    static DailyTransaction purchase(String amount, String date) {
        String record = String.format("%-16s%-2s%-4s%-10s%-100s%s%-9s%-50s%-50s%-10s%s%-26s",
                "0000000000000001", "01", "0001", "POS TERM", "Purchase", amount, "800000000",
                "Merchant", "City", "12345", CARD, date + " 12:00:00.000000");
        return new DailyTransaction(String.format("%-350s", record));
    }
}
