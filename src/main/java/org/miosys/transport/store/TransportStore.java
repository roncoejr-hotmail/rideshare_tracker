package org.miosys.transport.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.miosys.transport.domain.ComparisonReport;
import org.miosys.transport.domain.Page;
import org.miosys.transport.domain.RecurringCost;
import org.miosys.transport.domain.Rental;
import org.miosys.transport.domain.Ride;
import org.miosys.transport.domain.TenantSettings;
import org.miosys.transport.domain.Enums.Side;

/** Everything is scoped to the bound principal's tenant id. */
public interface TransportStore {

    record RideQuery(
            Optional<String> region,
            Optional<String> currency,
            Optional<String> provider,
            Optional<LocalDate> from,
            Optional<LocalDate> to) {}

    record RentalQuery(
            Optional<String> purpose,
            Optional<String> region,
            Optional<String> currency,
            Optional<String> company,
            Optional<LocalDate> from,
            Optional<LocalDate> to) {}

    Upserted<Ride> putRide(Ride ride);

    Optional<Ride> patchRide(String emailMessageId, JsonNode patch);

    Optional<Ride> ride(String emailMessageId);

    boolean deleteRide(String emailMessageId);

    Page<Ride> rides(RideQuery query, int page, int size);

    Upserted<Rental> putRental(Rental rental);

    Optional<Rental> patchRental(String company, String confirmationNo, JsonNode patch);

    Optional<Rental> rental(String company, String confirmationNo);

    boolean deleteRental(String company, String confirmationNo);

    Page<Rental> rentals(RentalQuery query, int page, int size);

    Upserted<RecurringCost> putRecurringCost(RecurringCost cost);

    Optional<RecurringCost> patchRecurringCost(String name, LocalDate from, JsonNode patch);

    Optional<RecurringCost> recurringCost(String name, LocalDate from);

    boolean deleteRecurringCost(String name, LocalDate from);

    List<RecurringCost> recurringCosts(Optional<Side> side, Optional<LocalDate> activeOn);

    Optional<TenantSettings> settings();

    Upserted<TenantSettings> putSettings(TenantSettings settings);

    /**
     * Runs the comparison. A null asOf means today in the tenant's time zone.
     * Throws Conflict when the tenant has no settings.
     */
    ComparisonReport comparison(LocalDate asOf);
}
