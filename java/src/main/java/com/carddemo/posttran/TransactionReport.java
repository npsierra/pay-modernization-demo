package com.carddemo.posttran;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * CBTRN03C, the daily transaction detail report: transactions (already sorted by card) are
 * listed with their account, type and category, with account, page and grand totals.
 * Each report line is one 133-byte record (copybook CVTRA07Y).
 */
final class TransactionReport {
    static final int RECORD_LENGTH = 133;
    static final int PAGE_SIZE = 20;
    private static final String BLANK_CARD = " ".repeat(16);
    private static final String DASHES = "-".repeat(RECORD_LENGTH);

    private final KeyedFile<CardXref> xrefs;
    private final KeyedFile<String> transactionTypes;
    private final KeyedFile<String> transactionCategories;
    private final Consumer<String> sysout;
    private final List<String> report = new ArrayList<>();

    private String startDate = " ".repeat(10);
    private String endDate = " ".repeat(10);
    private boolean firstTime = true;
    private long lineCounter;
    private BigDecimal pageTotal = BigDecimal.ZERO;
    private BigDecimal accountTotal = BigDecimal.ZERO;
    private BigDecimal grandTotal = BigDecimal.ZERO;
    private String currentCard = BLANK_CARD;
    /** XREF-ACCT-ID before any lookup (a blank first card skips it): zeros. */
    private String accountId = "0".repeat(11);
    private String typeDescription;
    private String categoryDescription;

    TransactionReport(KeyedFile<CardXref> xrefs, KeyedFile<String> transactionTypes,
            KeyedFile<String> transactionCategories, Consumer<String> sysout) {
        this.xrefs = xrefs;
        this.transactionTypes = transactionTypes;
        this.transactionCategories = transactionCategories;
        this.sysout = sysout;
    }

    /** The report records written so far (also after an abend, like the partial TRANREPT). */
    List<String> records() {
        return Collections.unmodifiableList(report);
    }

    /**
     * PROCEDURE DIVISION main loop. Reads the date range, then each transaction: a processing
     * date outside the range ends the loop at once ({@code NEXT SENTENCE}, no totals). At end
     * of file the last transaction's amount is added again and the page and grand totals are
     * written; the last account's total never is.
     *
     * @param dateParm the DATEPARM record, or {@code null} when the file is empty
     * @param transactions the TRANFILE records, padded to 350 bytes
     */
    void run(String dateParm, List<String> transactions) {
        boolean endOfFile = !readDateParm(dateParm);
        String transaction = Transaction.INITIAL;
        int next = 0;
        while (!endOfFile) {
            // 1000-TRANFILE-GET-NEXT: at end of file the record area keeps the last record.
            if (next < transactions.size()) {
                transaction = transactions.get(next++);
            } else {
                endOfFile = true;
            }
            if (!inDateRange(transaction)) {
                break;
            }
            if (!endOfFile) {
                sysout.accept(transaction);
                String card = Transaction.cardNumber(transaction);
                if (!currentCard.equals(card)) {
                    if (!firstTime) {
                        writeAccountTotals();
                    }
                    currentCard = card;
                    lookupXref(card);
                }
                lookupTransactionType(Transaction.typeCode(transaction));
                lookupTransactionCategory(Transaction.typeCode(transaction) + Transaction.categoryCode(transaction));
                writeTransactionReport(transaction);
            } else {
                String amount = Transaction.amountField(transaction);
                sysout.accept("TRAN-AMT " + ZonedDecimal.display(amount, 2));
                sysout.accept("WS-PAGE-TOTAL" + ZonedDecimal.display(ZonedDecimal.encode(pageTotal, 9, 2), 2));
                BigDecimal value = ZonedDecimal.decode(amount, 2);
                pageTotal = add(pageTotal, value);
                accountTotal = add(accountTotal, value);
                writePageTotals();
                writeGrandTotals();
            }
        }
    }

    /** 0550-DATEPARM-READ: start date (cols 1-10) and end date (cols 12-21); false at end of file. */
    boolean readDateParm(String dateParm) {
        if (dateParm == null) {
            return false;
        }
        startDate = dateParm.substring(0, 10);
        endDate = dateParm.substring(11, 21);
        sysout.accept("Reporting from " + startDate + " to " + endDate);
        return true;
    }

    /** Main loop date test: TRAN-PROC-TS(1:10) between WS-START-DATE and WS-END-DATE (text compare). */
    boolean inDateRange(String transaction) {
        String processingDate = Transaction.processingDate(transaction);
        return processingDate.compareTo(startDate) >= 0 && processingDate.compareTo(endDate) <= 0;
    }

    /** 1100-WRITE-TRANSACTION-REPORT: headers first time, page break every 20 lines, then the detail. */
    void writeTransactionReport(String transaction) {
        if (firstTime) {
            firstTime = false;
            writeHeaders();
        }
        if (lineCounter % PAGE_SIZE == 0) {
            writePageTotals();
            writeHeaders();
        }
        BigDecimal amount = ZonedDecimal.decode(Transaction.amountField(transaction), 2);
        pageTotal = add(pageTotal, amount);
        accountTotal = add(accountTotal, amount);
        writeDetail(transaction);
    }

    /** 1110-WRITE-PAGE-TOTALS: the page total is added to the grand total, then reset. */
    void writePageTotals() {
        write(totalLine("Page Total", 11, 86, pageTotal));
        grandTotal = add(grandTotal, pageTotal);
        pageTotal = BigDecimal.ZERO;
        lineCounter++;
        write(DASHES);
        lineCounter++;
    }

    /** 1120-WRITE-ACCOUNT-TOTALS. */
    void writeAccountTotals() {
        write(totalLine("Account Total", 13, 84, accountTotal));
        accountTotal = BigDecimal.ZERO;
        lineCounter++;
        write(DASHES);
        lineCounter++;
    }

    /** 1110-WRITE-GRAND-TOTALS: does not count a line. */
    void writeGrandTotals() {
        write(totalLine("Grand Total", 11, 86, grandTotal));
    }

    /** 1120-WRITE-HEADERS: report name with the date range, blank line, column headings, dashes. */
    void writeHeaders() {
        write(pad("DALYREPT", 38) + pad("Daily Transaction Report", 41) + pad("Date Range: ", 12)
                + startDate + " to " + endDate);
        lineCounter++;
        write("");
        lineCounter++;
        write(pad("Transaction ID", 17) + pad("Account ID", 12) + pad("Transaction Type", 19)
                + pad("Tran Category", 35) + pad("Tran Source", 14) + " " + pad("        Amount", 16));
        lineCounter++;
        write(DASHES);
        lineCounter++;
    }

    /** 1120-WRITE-DETAIL. */
    void writeDetail(String transaction) {
        write(Transaction.id(transaction) + " " + accountId + " " + Transaction.typeCode(transaction) + "-"
                + typeDescription.substring(0, 15) + " " + Transaction.categoryCode(transaction) + "-"
                + categoryDescription.substring(0, 29) + " " + Transaction.source(transaction) + "    "
                + edit(ZonedDecimal.decode(Transaction.amountField(transaction), 2), '-') + "  ");
        lineCounter++;
    }

    /** 1500-A-LOOKUP-XREF: an unknown card abends (status 23). */
    void lookupXref(String card) {
        CardXref xref = xrefs.read(card).orElseThrow(() -> new AbendException(
                "INVALID CARD NUMBER : " + card + "\nFILE STATUS IS: NNNN0023"));
        accountId = xref.accountId();
    }

    /** 1500-B-LOOKUP-TRANTYPE: an unknown type abends (status 23). */
    void lookupTransactionType(String type) {
        String record = transactionTypes.read(type).orElseThrow(() -> new AbendException(
                "INVALID TRANSACTION TYPE : " + type + "\nFILE STATUS IS: NNNN0023"));
        typeDescription = record.substring(2, 52);
    }

    /** 1500-C-LOOKUP-TRANCATG: an unknown type + category abends (status 23). */
    void lookupTransactionCategory(String key) {
        String record = transactionCategories.read(key).orElseThrow(() -> new AbendException(
                "INVALID TRAN CATG KEY : " + key + "\nFILE STATUS IS: NNNN0023"));
        categoryDescription = record.substring(6, 56);
    }

    private void write(String line) {
        report.add(pad(line, RECORD_LENGTH));
    }

    private static String totalLine(String label, int labelWidth, int dots, BigDecimal total) {
        return pad(label, labelWidth) + ".".repeat(dots) + edit(total, '+') + " ".repeat(20);
    }

    private static BigDecimal add(BigDecimal total, BigDecimal amount) {
        return ZonedDecimal.truncate(total.add(amount), 9, 2);
    }

    /**
     * MOVE of an S9(09)V99 value to {@code -ZZZ,ZZZ,ZZZ.ZZ} or {@code +ZZZ,ZZZ,ZZZ.ZZ}: leading
     * zeros and their commas become spaces, the sign stays in column 1 ({@code '-'} picture:
     * space when positive), a zero value is all spaces, and a negative zero prints like zero.
     */
    static String edit(BigDecimal value, char positiveSign) {
        BigDecimal truncated = ZonedDecimal.truncate(value, 9, 2);
        if (truncated.signum() == 0) {
            return " ".repeat(15);
        }
        String digits = String.format(Locale.ROOT, "%011d", truncated.unscaledValue().abs());
        String grouped = digits.substring(0, 3) + "," + digits.substring(3, 6) + "," + digits.substring(6, 9);
        StringBuilder integer = new StringBuilder(grouped);
        for (int i = 0; i < integer.length() && (integer.charAt(i) == '0' || integer.charAt(i) == ','); i++) {
            integer.setCharAt(i, ' ');
        }
        char sign = truncated.signum() < 0 ? '-' : positiveSign == '+' ? '+' : ' ';
        return sign + integer.toString() + "." + digits.substring(9);
    }

    static String pad(String text, int width) {
        return String.format("%-" + width + "." + width + "s", text);
    }

    /** Field positions in the 350-byte TRAN-RECORD (copybook CVTRA05Y layout in the FD). */
    static final class Transaction {
        static final int LENGTH = 350;
        /** WORKING-STORAGE TRAN-RECORD before the first READ: spaces, numeric fields zero. */
        static final String INITIAL = " ".repeat(18) + "0000" + " ".repeat(110) + "00000000000"
                + "000000000" + " ".repeat(208);

        private Transaction() {
        }

        static String id(String record) {
            return record.substring(0, 16);
        }

        static String typeCode(String record) {
            return record.substring(16, 18);
        }

        static String categoryCode(String record) {
            return record.substring(18, 22);
        }

        static String source(String record) {
            return record.substring(22, 32);
        }

        static String amountField(String record) {
            return record.substring(132, 143);
        }

        static String cardNumber(String record) {
            return record.substring(262, 278);
        }

        static String processingDate(String record) {
            return record.substring(304, 314);
        }
    }
}
