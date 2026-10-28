package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ZonedDecimalTest {

    @Test
    void decodesEveryOverpunchDigit() {
        String positive = "{ABCDEFGHI";
        String negative = "}JKLMNOPQR";
        for (int digit = 0; digit <= 9; digit++) {
            assertEquals(new BigDecimal("12.3" + digit), ZonedDecimal.decode("0000123" + positive.charAt(digit), 2));
            assertEquals(new BigDecimal("-12.3" + digit), ZonedDecimal.decode("0000123" + negative.charAt(digit), 2));
        }
    }

    @Test
    void decodesSampleAmounts() {
        assertEquals(new BigDecimal("504.77"), ZonedDecimal.decode("0000005047G", 2));
        assertEquals(new BigDecimal("-919.00"), ZonedDecimal.decode("0000009190}", 2));
        assertEquals(new BigDecimal("12.34"), ZonedDecimal.decode("00001234", 2));
    }

    @Test
    void encodesWithOverpunchSign() {
        assertEquals("00000011648G", ZonedDecimal.encode(new BigDecimal("1164.87"), 10, 2));
        assertEquals("00000000707P", ZonedDecimal.encode(new BigDecimal("-70.77"), 10, 2));
        assertEquals("0000000000{", ZonedDecimal.encode(BigDecimal.ZERO, 9, 2));
    }

    @Test
    void roundTrips() {
        for (String field : new String[] {"0000005047G", "0000009190}", "9999999999R"}) {
            assertEquals(field, ZonedDecimal.encode(ZonedDecimal.decode(field, 2), 9, 2));
        }
    }

    @Test
    void truncatesHighOrderDigitsLikeCobol() {
        assertEquals(new BigDecimal("234567890.12"),
                ZonedDecimal.truncate(new BigDecimal("1234567890.129"), 9, 2));
        assertEquals(new BigDecimal("-234567890.12"),
                ZonedDecimal.truncate(new BigDecimal("-1234567890.129"), 9, 2));
    }

    @Test
    void rejectsInvalidField() {
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decode("00000 5047G", 2));
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decode("0000005047*", 2));
    }
}
