package com.carddemo.posttran;

/** An error that ends the run abnormally, like the COBOL 9999-ABEND-PROGRAM path. */
final class AbendException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AbendException(String message) {
        super(message);
    }

    AbendException(String message, Throwable cause) {
        super(message, cause);
    }
}
