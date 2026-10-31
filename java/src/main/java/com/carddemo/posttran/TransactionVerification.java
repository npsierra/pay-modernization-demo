package com.carddemo.posttran;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Port of CBTRN01C: reads each daily transaction, looks its card up in the cross-reference
 * file and reads the card's account, reporting every step on SYSOUT. Nothing is written or
 * updated. Behaviour kept from the COBOL: after end of file the loop body still looks up the
 * last record's card once more (a blank card when the file is empty).
 */
final class TransactionVerification {
    private final KeyedFile<CardXref> xrefs;
    private final KeyedFile<String> accounts;
    private final Consumer<String> sysout;

    /** DALYTRAN-RECORD: WORKING-STORAGE starts as spaces; READ INTO replaces it, EOF keeps it. */
    private String dailyRecord = " ".repeat(DailyTransaction.LENGTH);
    private boolean endOfDailyTransFile;

    TransactionVerification(KeyedFile<CardXref> xrefs, KeyedFile<String> accounts, Consumer<String> sysout) {
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.sysout = sysout;
    }

    /** MAIN-PARA: the loop between the OPENs and the CLOSEs. */
    void run(List<String> dailyRecords) {
        Iterator<String> daily = dailyRecords.iterator();
        while (!endOfDailyTransFile) {
            getNextDailyTransaction(daily);
            if (!endOfDailyTransFile) {
                sysout.accept(dailyRecord);
            }
            String cardNumber = dailyRecord.substring(262, 278);
            Optional<CardXref> xref = lookupXref(cardNumber);
            if (xref.isPresent()) {
                String accountId = xref.get().accountId();
                if (!readAccount(accountId)) {
                    sysout.accept("ACCOUNT " + accountId + " NOT FOUND");
                }
            } else {
                sysout.accept("CARD NUMBER " + cardNumber + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-"
                        + dailyRecord.substring(0, 16));
            }
        }
    }

    /** 1000-DALYTRAN-GET-NEXT: READ DALYTRAN-FILE INTO DALYTRAN-RECORD; status 10 sets end of file. */
    private void getNextDailyTransaction(Iterator<String> daily) {
        if (daily.hasNext()) {
            dailyRecord = daily.next();
        } else {
            endOfDailyTransFile = true;
        }
    }

    /** 2000-LOOKUP-XREF: random READ of XREF-FILE by card number. */
    private Optional<CardXref> lookupXref(String cardNumber) {
        Optional<CardXref> xref = xrefs.read(cardNumber);
        if (xref.isEmpty()) {
            sysout.accept("INVALID CARD NUMBER FOR XREF");
        } else {
            sysout.accept("SUCCESSFUL READ OF XREF");
            sysout.accept("CARD NUMBER: " + xref.get().cardNumber());
            sysout.accept("ACCOUNT ID : " + xref.get().accountId());
            sysout.accept("CUSTOMER ID: " + xref.get().customerId());
        }
        return xref;
    }

    /** 3000-READ-ACCOUNT: random READ of ACCOUNT-FILE by account ID; false on INVALID KEY. */
    private boolean readAccount(String accountId) {
        if (accounts.read(accountId).isEmpty()) {
            sysout.accept("INVALID ACCOUNT NUMBER FOUND");
            return false;
        }
        sysout.accept("SUCCESSFUL READ OF ACCOUNT FILE");
        return true;
    }
}
