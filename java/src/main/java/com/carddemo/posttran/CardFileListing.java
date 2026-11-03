package com.carddemo.posttran;

import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Port of CBACT02C: reads the card master file front to back in card-number order and
 * displays each 150-byte record (CVACT02Y CARD-RECORD) on SYSOUT. Nothing is written or
 * updated.
 */
final class CardFileListing {
    static final int RECORD_LENGTH = 150;
    static final int KEY_LENGTH = 16;

    private final Consumer<String> sysout;

    /** CARD-RECORD: only displayed after a successful READ INTO, which replaces all 150 bytes. */
    private String cardRecord = " ".repeat(RECORD_LENGTH);
    /** END-OF-FILE: VALUE 'N'. */
    private boolean endOfFile;

    CardFileListing(Consumer<String> sysout) {
        this.sysout = sysout;
    }

    /** PROCEDURE DIVISION loop: PERFORM UNTIL END-OF-FILE = 'Y', DISPLAY CARD-RECORD after each good read. */
    void run(Iterable<String> cardFile) {
        Iterator<String> cards = cardFile.iterator();
        while (!endOfFile) {
            getNextCard(cards);
            if (!endOfFile) {
                sysout.accept(cardRecord);
            }
        }
    }

    /**
     * 1000-CARDFILE-GET-NEXT: sequential READ CARDFILE-FILE INTO CARD-RECORD; status 00 keeps
     * going, 10 sets END-OF-FILE to 'Y' (any other status abends; it cannot occur on a file
     * that was loaded and opened successfully).
     */
    private void getNextCard(Iterator<String> cards) {
        if (cards.hasNext()) {
            cardRecord = cards.next();
        } else {
            endOfFile = true;
        }
    }
}
