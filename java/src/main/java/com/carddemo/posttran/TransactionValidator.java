package com.carddemo.posttran;

import java.math.BigDecimal;
import java.util.Optional;

/** 1500-VALIDATE-TRAN: card lookup, account lookup, credit limit and expiration checks. */
final class TransactionValidator {

    record Validation(CardXref xref, Account account, RejectReason reason) {
        boolean accepted() {
            return reason == null;
        }
    }

    private final KeyedFile<CardXref> xrefs;
    private final KeyedFile<Account> accounts;

    TransactionValidator(KeyedFile<CardXref> xrefs, KeyedFile<Account> accounts) {
        this.xrefs = xrefs;
        this.accounts = accounts;
    }

    Validation validate(DailyTransaction transaction) {
        Optional<CardXref> xref = xrefs.read(transaction.cardNumber());
        if (xref.isEmpty()) {
            return new Validation(null, null, RejectReason.INVALID_CARD);
        }
        Optional<Account> account = accounts.read(xref.get().accountId());
        if (account.isEmpty()) {
            return new Validation(xref.get(), null, RejectReason.ACCOUNT_NOT_FOUND);
        }
        return new Validation(xref.get(), account.get(), checkAccount(account.get(), transaction));
    }

    /**
     * Both checks always run and the later one wins, as in 1500-B-LOOKUP-ACCT. The limit
     * check is kept exactly as written: cycle credit - cycle debit + amount, where returns
     * are negative debits, held in WS-TEMP-BAL (PIC S9(09)V99).
     */
    private static RejectReason checkAccount(Account account, DailyTransaction transaction) {
        RejectReason reason = null;
        BigDecimal projected = ZonedDecimal.truncate(
                account.cycleCredit().subtract(account.cycleDebit()).add(transaction.amount()), 9, 2);
        if (account.creditLimit().compareTo(projected) < 0) {
            reason = RejectReason.OVERLIMIT;
        }
        if (account.expirationDate().compareTo(transaction.originTimestamp().substring(0, 10)) < 0) {
            reason = RejectReason.EXPIRED_ACCOUNT;
        }
        return reason;
    }
}
