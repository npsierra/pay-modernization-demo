package com.carddemo.posttran;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The CBTRN02C job: validates each daily transaction, posts the valid ones to the category
 * balances, accounts and transaction master, and collects the rejects.
 */
final class DailyTransactionPosting {

    record Result(int processed, int rejected) {
        int returnCode() {
            return rejected > 0 ? 4 : 0;
        }
    }

    private final KeyedFile<Account> accounts;
    private final KeyedFile<CategoryBalance> categoryBalances;
    private final KeyedFile<String> transactions = new KeyedFile<>("TRANSACTION FILE", r -> r.substring(0, 16));
    private final List<String> rejects = new ArrayList<>();
    private final TransactionValidator validator;
    private final Supplier<String> processingTimestamp;
    private final Consumer<String> sysout;

    /**
     * COBOL INITIALIZE skips FILLER, so a newly created category balance record keeps the
     * FILLER of the last record read into TRAN-CAT-BAL-RECORD (spaces before the first read).
     */
    private String categoryFiller = " ".repeat(CategoryBalance.LENGTH - CategoryBalance.KEY_LENGTH - 11);

    DailyTransactionPosting(KeyedFile<CardXref> xrefs, KeyedFile<Account> accounts,
            KeyedFile<CategoryBalance> categoryBalances, Supplier<String> processingTimestamp,
            Consumer<String> sysout) {
        this.accounts = accounts;
        this.categoryBalances = categoryBalances;
        this.validator = new TransactionValidator(xrefs, accounts);
        this.processingTimestamp = processingTimestamp;
        this.sysout = sysout;
    }

    Result run(List<DailyTransaction> dailyTransactions) {
        sysout.accept("START OF EXECUTION OF PROGRAM CBTRN02C");
        int processed = 0;
        for (DailyTransaction transaction : dailyTransactions) {
            processed++;
            TransactionValidator.Validation validation = validator.validate(transaction);
            if (validation.accepted()) {
                post(transaction, validation);
            } else {
                rejects.add(transaction.record() + validation.reason().trailer());
            }
        }
        Result result = new Result(processed, rejects.size());
        sysout.accept(String.format("TRANSACTIONS PROCESSED :%09d", result.processed()));
        sysout.accept(String.format("TRANSACTIONS REJECTED  :%09d", result.rejected()));
        sysout.accept("END OF EXECUTION OF PROGRAM CBTRN02C");
        return result;
    }

    /** 2000-POST-TRANSACTION. */
    private void post(DailyTransaction transaction, TransactionValidator.Validation validation) {
        String timestamp = processingTimestamp.get();
        updateCategoryBalance(transaction, validation.xref().accountId());
        validation.account().post(transaction.amount());
        transactions.write(transaction.toTransactionRecord(timestamp));
    }

    /** 2700-UPDATE-TCATBAL. */
    private void updateCategoryBalance(DailyTransaction transaction, String accountId) {
        String key = CategoryBalance.key(accountId, transaction.typeCode(), transaction.categoryCode());
        BigDecimal amount = transaction.amount();
        CategoryBalance existing = categoryBalances.read(key).orElse(null);
        if (existing != null) {
            categoryFiller = existing.filler();
            existing.add(amount);
            return;
        }
        sysout.accept("TCATBAL record not found for key : " + key + ".. Creating.");
        CategoryBalance created = CategoryBalance.create(key, categoryFiller);
        created.add(amount);
        categoryBalances.write(created);
    }

    List<String> transactionRecords() {
        return new ArrayList<>(transactions.records());
    }

    List<String> rejectRecords() {
        return Collections.unmodifiableList(rejects);
    }

    KeyedFile<Account> accounts() {
        return accounts;
    }

    KeyedFile<CategoryBalance> categoryBalances() {
        return categoryBalances;
    }
}
