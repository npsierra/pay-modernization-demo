package com.carddemo.posttran;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Signed zoned-decimal fields ({@code PIC S9(n)V99} in DISPLAY format) as stored in the
 * CardDemo data: the last character carries both the last digit and the sign
 * ({@code '{'}/{@code 'A'..'I'} = +0..+9, {@code '}'}/{@code 'J'..'R'} = -0..-9).
 */
final class ZonedDecimal {
    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private ZonedDecimal() {
    }

    static BigDecimal decode(String field, int scale) {
        if (field.isEmpty()) {
            throw new IllegalArgumentException("Empty signed numeric field");
        }
        char last = field.charAt(field.length() - 1);
        int digit;
        boolean negative = false;
        if (POSITIVE.indexOf(last) >= 0) {
            digit = POSITIVE.indexOf(last);
        } else if (NEGATIVE.indexOf(last) >= 0) {
            digit = NEGATIVE.indexOf(last);
            negative = true;
        } else if (last >= '0' && last <= '9') {
            digit = last - '0';
        } else {
            throw new IllegalArgumentException("Invalid signed numeric field: '" + field + "'");
        }
        String digits = field.substring(0, field.length() - 1) + digit;
        if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid signed numeric field: '" + field + "'");
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits), scale);
        return negative ? value.negate() : value;
    }

    static String encode(BigDecimal value, int integerDigits, int scale) {
        BigInteger unscaled = truncate(value, integerDigits, scale).unscaledValue();
        int width = integerDigits + scale;
        String digits = String.format("%0" + width + "d", unscaled.abs());
        int last = digits.charAt(width - 1) - '0';
        char signed = unscaled.signum() < 0 ? NEGATIVE.charAt(last) : POSITIVE.charAt(last);
        return digits.substring(0, width - 1) + signed;
    }

    /**
     * MOVE between two signed DISPLAY fields of the same picture (GnuCOBOL, -fsign=EBCDIC):
     * the digits and the sign are kept, and a plain last digit (no sign) becomes a positive
     * overpunch. A negative zero stays negative ({@code '}'}).
     */
    static String move(String field) {
        decode(field, 0);
        char last = field.charAt(field.length() - 1);
        if (last >= '0' && last <= '9') {
            return field.substring(0, field.length() - 1) + POSITIVE.charAt(last - '0');
        }
        return field;
    }

    /**
     * DISPLAY of a signed DISPLAY field as GnuCOBOL prints it: a sign by value ('+' for any
     * zero), the integer digits, a decimal point and the decimals, e.g. {@code -0000000194.00}.
     */
    static String display(String field, int scale) {
        BigDecimal value = decode(field, scale);
        String digits = field.substring(0, field.length() - 1)
                + value.unscaledValue().abs().mod(BigInteger.TEN);
        int point = digits.length() - scale;
        return (value.signum() < 0 ? "-" : "+") + digits.substring(0, point) + "." + digits.substring(point);
    }

    /**
     * COBOL arithmetic without ON SIZE ERROR keeps only the low-order digits that fit the
     * receiving field; extra decimals are dropped, not rounded.
     */
    static BigDecimal truncate(BigDecimal value, int integerDigits, int scale) {
        BigInteger unscaled = value.setScale(scale, RoundingMode.DOWN).unscaledValue();
        BigInteger kept = unscaled.abs().mod(BigInteger.TEN.pow(integerDigits + scale));
        return new BigDecimal(unscaled.signum() < 0 ? kept.negate() : kept, scale);
    }
}
