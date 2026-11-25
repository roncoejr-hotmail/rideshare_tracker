package org.miosys.transport.domain;

import java.time.LocalDate;
import java.util.List;

/** Full comparison report. Cumulative values and totals sum the rounded months. */
public record ComparisonReport(
        String currency, LocalDate comparisonStart, LocalDate asOf, List<CumulativeMonth> months, Totals totals) {

    public record CumulativeMonth(MonthlyComparison month, long cumulativeOld, long cumulativeNew,
            long cumulativeSaved) {}

    public record Totals(
            int days,
            long oldCents,
            long newCents,
            long savedCents,
            int rideCount,
            int rentalCount,
            int excludedRides,
            int excludedRentals) {}
}
