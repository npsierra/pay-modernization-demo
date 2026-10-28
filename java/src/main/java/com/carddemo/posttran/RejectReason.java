package com.carddemo.posttran;

/** Validation failure codes written to the rejects file (WS-VALIDATION-TRAILER). */
enum RejectReason {
    INVALID_CARD(100, "INVALID CARD NUMBER FOUND"),
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),
    OVERLIMIT(102, "OVERLIMIT TRANSACTION"),
    EXPIRED_ACCOUNT(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

    private final int code;
    private final String description;

    RejectReason(int code, String description) {
        this.code = code;
        this.description = description;
    }

    int code() {
        return code;
    }

    /** 80-byte trailer: 4-digit code followed by a 76-byte description. */
    String trailer() {
        return String.format("%04d%-76s", code, description);
    }
}
