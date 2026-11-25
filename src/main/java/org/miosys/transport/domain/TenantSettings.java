package org.miosys.transport.domain;

import java.time.LocalDate;

/** One row per tenant. The comparison cannot run without it. */
public record TenantSettings(
        LocalDate comparisonStart,
        String currency,
        String homeRegion,
        String personalTag,
        String timeZone,
        String createdAt,
        String updatedAt) {}
