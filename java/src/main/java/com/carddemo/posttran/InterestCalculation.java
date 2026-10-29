package com.carddemo.posttran;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Java port of CBACT04C (interest calculation, JCL INTCALC). Reads the category balances in
 * key order; for each one with a non-zero rate it writes an interest transaction, and when
 * the account changes it adds the previous account's interest to its balance and zeroes its
 * cycle credit and debit.
 *
 * <p>Kept as in the original: the last account in the file is never updated (the COBOL only
 * calls 1050-UPDATE-ACCOUNT when the account number changes; its end-of-file branch is never
 * reached), interest is charged on negative balances too, and amounts are truncated, not rounded.
 */
final class InterestCalculation {
    private static final BigDecimal MONTHS_TIMES_PERCENT = BigDecimal.valueOf(1200);

    private final KeyedFile<Account> accounts;
    private final Map<String, CardXref> xrefByAccount = new LinkedHashMap<>();
    private final KeyedFile<DisclosureGroup> disclosureGroups;
    private final String parmDate;
    private final Supplier<String> timestamp;
    private final Consumer<String> sysout;
    private final List<String> transactions = new ArrayList<>();

    InterestCalculation(KeyedFile<Account> accounts, KeyedFile<CardXref> xrefs,
            KeyedFile<DisclosureGroup> disclosureGroups, String parmDate, Supplier<String> timestamp,
            Consumer<String> sysout) {
        this.accounts = accounts;
        // Alternate index on account ID (non-unique): a keyed read returns the first card.
        for (CardXref xref : xrefs.records()) {
            xrefByAccount.putIfAbsent(xref.accountId(), xref);
        }
        this.disclosureGroups = disclosureGroups;
        this.parmDate = String.format("%-10.10s", parmDate);
        this.timestamp = timestamp;
        this.sysout = sysout;
    }

    void run(Iterable<CategoryBalance> categoryBalances) {
        sysout.accept("START OF EXECUTION OF PROGRAM CBACT04C");
        String lastAccountId = null;
        Account account = null;
        CardXref xref = null;
        BigDecimal totalInterest = BigDecimal.ZERO;
        int suffix = 0;
        for (CategoryBalance balance : categoryBalances) {
            String record = balance.toRecord();
            sysout.accept(record);
            String accountId = record.substring(0, 11);
            if (!accountId.equals(lastAccountId)) {
                if (account != null) {
                    account.closeCycle(totalInterest);
                }
                totalInterest = BigDecimal.ZERO;
                lastAccountId = accountId;
                account = readAccount(accountId);
                xref = readXref(accountId);
            }
            BigDecimal rate = interestRate(account.groupId(), record.substring(11, 13), record.substring(13, 17));
            if (rate.signum() != 0) {
                BigDecimal monthly = ZonedDecimal.truncate(
                        balance.balance().multiply(rate).divide(MONTHS_TIMES_PERCENT, 10, RoundingMode.DOWN), 9, 2);
                totalInterest = ZonedDecimal.truncate(totalInterest.add(monthly), 9, 2);
                suffix = (suffix + 1) % 1_000_000;
                transactions.add(interestTransaction(String.format(Locale.ROOT, "%06d", suffix), account, xref, monthly));
            }
        }
        sysout.accept("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    List<String> transactionRecords() {
        return transactions;
    }

    KeyedFile<Account> accounts() {
        return accounts;
    }

    private Account readAccount(String accountId) {
        return accounts.read(accountId).orElseThrow(() -> notFound(accountId));
    }

    private CardXref readXref(String accountId) {
        CardXref xref = xrefByAccount.get(accountId);
        if (xref == null) {
            throw notFound(accountId);
        }
        return xref;
    }

    private AbendException notFound(String accountId) {
        sysout.accept("ACCOUNT NOT FOUND: " + accountId);
        return new AbendException("FILE STATUS IS: NNNN0023");
    }

    /** 1200-GET-INTEREST-RATE: the account's group, else the DEFAULT group. */
    private BigDecimal interestRate(String groupId, String typeCode, String categoryCode) {
        DisclosureGroup group = disclosureGroups.read(DisclosureGroup.key(groupId, typeCode, categoryCode))
                .orElse(null);
        if (group == null) {
            sysout.accept("DISCLOSURE GROUP RECORD MISSING");
            sysout.accept("TRY WITH DEFAULT GROUP CODE");
            group = disclosureGroups.read(DisclosureGroup.key(DisclosureGroup.DEFAULT_GROUP, typeCode, categoryCode))
                    .orElseThrow(() -> new AbendException("FILE STATUS IS: NNNN0023"));
        }
        return group.interestRate();
    }

    /** 1300-B-WRITE-TX: one 350-byte transaction record (copybook CVTRA05Y). */
    private String interestTransaction(String suffix, Account account, CardXref xref, BigDecimal amount) {
        String ts = timestamp.get();
        String record = parmDate + suffix
                + "01" + "0005"
                + String.format("%-10s", "System")
                + String.format("%-100s", "Int. for a/c " + account.id())
                + ZonedDecimal.encode(amount, 9, 2)
                + "000000000"
                + " ".repeat(50) + " ".repeat(50) + " ".repeat(10)
                + xref.cardNumber()
                + ts + ts
                + " ".repeat(20);
        if (record.length() != 350) {
            throw new IllegalStateException("transaction record is " + record.length() + " bytes");
        }
        return record;
    }
}
