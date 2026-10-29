package com.carddemo.posttran;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

/**
 * Packed-decimal fields ({@code USAGE COMP-3}): two digits per byte and a sign in the last
 * low nibble (C = positive, D = negative). {@code PIC S9(10)V99} takes 7 bytes.
 */
final class PackedDecimal {
    private PackedDecimal() {
    }

    static int length(int integerDigits, int scale) {
        return (integerDigits + scale) / 2 + 1;
    }

    static byte[] encode(BigDecimal value, int integerDigits, int scale) {
        BigInteger unscaled = ZonedDecimal.truncate(value, integerDigits, scale).unscaledValue();
        int length = length(integerDigits, scale);
        String digits = String.format(Locale.ROOT, "%0" + (length * 2 - 1) + "d", unscaled.abs());
        String nibbles = digits + (unscaled.signum() < 0 ? 'D' : 'C');
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) Integer.parseInt(nibbles.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
