package org.miosys.transport.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.miosys.transport.domain.Enums.Region;

/** One personal rideshare trip. No purpose column: every stored ride is personal. */
public record Ride(
        String emailMessageId,
        String provider,
        Region region,
        LocalDate rideDate,
        Optional<Instant> pickupUtc,
        Optional<Instant> dropoffUtc,
        Optional<String> pickupAddr,
        Optional<String> dropoffAddr,
        Money fare,
        Money tip,
        Optional<String> paymentLast4,
        Optional<String> rider,
        String emailSubject,
        Optional<Instant> emailReceivedUtc,
        Optional<String> notes,
        String createdAt,
        String updatedAt) {

    public Money total() {
        return new Money(fare.cents() + tip.cents(), fare.currency());
    }
}
