package org.miosys.transport.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.miosys.transport.domain.Enums.Purpose;
import org.miosys.transport.domain.Enums.Region;

/** One car rental at gross cost plus its adjustments. */
public record Rental(
        String company,
        String confirmationNo,
        Optional<String> bookingChannel,
        LocalDate startDate,
        LocalDate endDate,
        Optional<String> location,
        Purpose purpose,
        Region region,
        Money gross,
        List<Adjustment> adjustments,
        Optional<String> paymentLast4,
        Optional<String> emailMessageId,
        Optional<String> emailSubject,
        Optional<String> notes,
        String createdAt,
        String updatedAt) {

    public Money net() {
        long off =
                adjustments.stream()
                        .filter(Adjustment::affectsNet)
                        .mapToLong(Adjustment::amountCents)
                        .sum();
        return new Money(gross.cents() - off, gross.currency());
    }
}
