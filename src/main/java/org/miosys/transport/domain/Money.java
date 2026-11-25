package org.miosys.transport.domain;

import java.math.BigDecimal;

/** Integer cents plus ISO currency. Never floating point. */
public record Money(long cents, String currency) {

    public Money {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("bad currency: " + currency);
        }
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount.movePointRight(2).longValueExact(), currency);
    }

    /** Always exactly 2 decimals on output. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(cents, 2);
    }
}
