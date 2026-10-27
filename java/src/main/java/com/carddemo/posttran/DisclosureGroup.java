package com.carddemo.posttran;

import java.math.BigDecimal;

/** One 50-byte disclosure group record (copybook CVTRA02Y): interest rate per group/type/category. */
record DisclosureGroup(String key, BigDecimal interestRate) {
    static final int LENGTH = 50;
    static final int KEY_LENGTH = 16;
    static final String DEFAULT_GROUP = String.format("%-10s", "DEFAULT");

    static DisclosureGroup parse(String record) {
        return new DisclosureGroup(record.substring(0, KEY_LENGTH),
                ZonedDecimal.decode(record.substring(KEY_LENGTH, KEY_LENGTH + 6), 2));
    }

    static String key(String groupId, String typeCode, String categoryCode) {
        return groupId + typeCode + categoryCode;
    }
}
