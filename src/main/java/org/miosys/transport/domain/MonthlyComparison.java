package org.miosys.transport.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/** One rounded month of the comparison. Money is integer cents. */
public record MonthlyComparison(
        YearMonth month,
        LocalDate windowFrom,
        LocalDate windowTo,
        int days,
        long oldCents,
        long rideCents,
        int rideCount,
        long rentalCents,
        int rentalCount,
        long recurringNewCents,
        int excludedRides,
        int excludedRentals) {

    public long newCents() {
        return rideCents + rentalCents + recurringNewCents;
    }

    public long savedCents() {
        return oldCents - newCents();
    }
}
