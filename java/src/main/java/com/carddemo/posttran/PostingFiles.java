package com.carddemo.posttran;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Reads the ASCII sample files and writes the job's outputs. Input lines may end in LF or
 * CRLF; keyed-file lines shorter than the record length are padded with spaces (the
 * cross-reference file omits its trailing FILLER). Outputs are fixed-length records, one
 * per line.
 */
final class PostingFiles {
    static final String DAILY_TRANSACTIONS = "dailytran.txt";
    static final String CARD_XREF = "cardxref.txt";
    static final String ACCOUNTS = "acctdata.txt";
    static final String CATEGORY_BALANCES = "tcatbal.txt";
    static final String DISCLOSURE_GROUPS = "discgrp.txt";

    static final String TRANSACTIONS_OUT = "TRANSACT.txt";
    static final String ACCOUNTS_OUT = "ACCTDATA.txt";
    static final String CATEGORY_BALANCES_OUT = "TCATBALF.txt";
    static final String REJECTS_OUT = "DALYREJS.txt";

    private PostingFiles() {
    }

    static List<DailyTransaction> readDailyTransactions(Path inputDir) {
        List<DailyTransaction> transactions = new ArrayList<>();
        for (String line : lines(inputDir.resolve(DAILY_TRANSACTIONS))) {
            transactions.add(new DailyTransaction(line));
        }
        return transactions;
    }

    static KeyedFile<CardXref> readCardXrefs(Path inputDir) {
        return load(inputDir.resolve(CARD_XREF), "CROSS REF FILE", CardXref.LENGTH,
                CardXref::parse, CardXref::cardNumber);
    }

    static KeyedFile<Account> readAccounts(Path inputDir) {
        return load(inputDir.resolve(ACCOUNTS), "ACCOUNT FILE", Account.LENGTH, Account::parse, Account::id);
    }

    static KeyedFile<CategoryBalance> readCategoryBalances(Path inputDir) {
        return load(inputDir.resolve(CATEGORY_BALANCES), "TRANSACTION BALANCE FILE", CategoryBalance.LENGTH,
                CategoryBalance::parse, CategoryBalance::key);
    }

    static KeyedFile<DisclosureGroup> readDisclosureGroups(Path inputDir) {
        return load(inputDir.resolve(DISCLOSURE_GROUPS), "DISCLOSURE GROUP FILE", DisclosureGroup.LENGTH,
                DisclosureGroup::parse, DisclosureGroup::key);
    }

    /** A keyed file of raw fixed-length records, keyed on the first {@code keyLength} bytes. */
    static KeyedFile<String> readRecords(Path file, String name, int length, int keyLength) {
        return load(file, name, length, Function.identity(), record -> record.substring(0, keyLength));
    }

    static void writeOutputs(DailyTransactionPosting posting, Path outputDir) {
        write(outputDir.resolve(TRANSACTIONS_OUT), posting.transactionRecords());
        write(outputDir.resolve(ACCOUNTS_OUT), map(posting.accounts().records(), Account::toRecord));
        write(outputDir.resolve(CATEGORY_BALANCES_OUT),
                map(posting.categoryBalances().records(), CategoryBalance::toRecord));
        write(outputDir.resolve(REJECTS_OUT), posting.rejectRecords());
    }

    private static <T> KeyedFile<T> load(Path file, String name, int length, Function<String, T> parse,
            Function<T, String> keyOf) {
        KeyedFile<T> keyed = new KeyedFile<>(name, keyOf);
        for (String line : lines(file)) {
            if (line.length() > length) {
                throw new AbendException(file + ": record longer than " + length + " bytes");
            }
            keyed.write(parse.apply(String.format(Locale.ROOT, "%-" + length + "s", line)));
        }
        return keyed;
    }

    private static List<String> lines(Path file) {
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                String record = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!record.isEmpty()) {
                    lines.add(record);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new AbendException("ERROR READING " + file + ": " + e.getMessage(), e);
        }
    }

    private static <T> List<String> map(Collection<T> records, Function<T, String> toRecord) {
        return records.stream().map(toRecord).toList();
    }

    private static void write(Path file, List<String> records) {
        try {
            Files.write(file, records, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
