package org.miosys.transport.domain;

import java.time.LocalDate;
import java.util.Optional;
import org.miosys.transport.domain.Enums.Period;
import org.miosys.transport.domain.Enums.Side;

/** A weekly or monthly cost on the baseline or current side. Always in tenant currency. */
public record RecurringCost(
        String name,
        LocalDate effectiveFrom,
        Optional<LocalDate> effectiveTo,
        Side side,
        Period period,
        long amountCents,
        Optional<String> notes,
        String createdAt,
        String updatedAt) {}
