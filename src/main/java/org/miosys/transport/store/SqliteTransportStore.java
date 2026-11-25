package org.miosys.transport.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.miosys.transport.auth.RequestContext;
import org.miosys.transport.db.Database;
import org.miosys.transport.db.Tx;
import org.miosys.transport.domain.Adjustment;
import org.miosys.transport.domain.ComparisonReport;
import org.miosys.transport.domain.Enums.Period;
import org.miosys.transport.domain.Enums.Purpose;
import org.miosys.transport.domain.Enums.Region;
import org.miosys.transport.domain.Enums.Side;
import org.miosys.transport.domain.Money;
import org.miosys.transport.domain.MonthlyComparison;
import org.miosys.transport.domain.Page;
import org.miosys.transport.domain.RecurringCost;
import org.miosys.transport.domain.Rental;
import org.miosys.transport.domain.Ride;
import org.miosys.transport.domain.TenantSettings;
import org.miosys.transport.http.ApiException.Conflict;
import org.miosys.transport.http.Inputs;
import org.miosys.transport.store.TransportStore.RentalQuery;
import org.miosys.transport.store.TransportStore.RideQuery;

/**
 * SQLite transport store. The tenant id comes only from the bound principal, and every
 * statement touching domain data filters by it first (adjustments through their rental).
 */
public final class SqliteTransportStore implements TransportStore {

    private static final String NOW = "strftime('%Y-%m-%dT%H:%M:%fZ','now')";

    private static final String RIDE_COLS =
            "email_message_id, provider, region, ride_date, pickup_utc, dropoff_utc, pickup_addr,"
                + " dropoff_addr, currency, fare_cents, tip_cents, payment_last4, rider, email_subject,"
                + " email_received_utc, notes, created_at, updated_at";

    private static final String RENTAL_COLS =
            "company, confirmation_no, booking_channel, start_date, end_date, location, purpose,"
                + " region, currency, gross_cents, payment_last4, email_message_id, email_subject,"
                + " notes, created_at, updated_at";

    private static final String COST_COLS =
            "name, effective_from, effective_to, side, period, amount_cents, notes, created_at, updated_at";

    private final Database db;

    public SqliteTransportStore(Database db) {
        this.db = db;
    }

    private static long tenantId() {
        return RequestContext.current().tenantId();
    }

    // ---- rides ----

    @Override
    public Upserted<Ride> putRide(Ride ride) {
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenant = tenantId();
                        boolean exists = rideExists(conn, tenant, ride.emailMessageId());
                        if (exists) {
                            updateRideRow(conn, tenant, ride);
                        } else {
                            insertRideRow(conn, tenant, ride);
                        }
                        return new Upserted<>(
                                !exists, readRide(conn, tenant, ride.emailMessageId()).orElseThrow());
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Optional<Ride> patchRide(String emailMessageId, JsonNode patch) {
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenant = tenantId();
                        Optional<Ride> stored = readRide(conn, tenant, emailMessageId);
                        if (stored.isEmpty()) {
                            return Optional.<Ride>empty();
                        }
                        TenantSettings s = readSettings(conn, tenant).orElse(null);
                        String defCur = s == null ? "USD" : s.currency();
                        String tag = s == null ? "[Personal]" : s.personalTag();
                        JsonNode merged = Inputs.mergedRide(stored.get(), patch);
                        boolean checkTag =
                                patch.has("emailSubject") && !patch.get("emailSubject").isNull();
                        Ride updated = Inputs.rideFromPut(merged, emailMessageId, defCur, tag, checkTag);
                        updateRideRow(conn, tenant, updated);
                        return readRide(conn, tenant, emailMessageId);
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Optional<Ride> ride(String emailMessageId) {
        try (Connection c = db.open()) {
            return readRide(c, tenantId(), emailMessageId);
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public boolean deleteRide(String emailMessageId) {
        try (Connection c = db.open();
                PreparedStatement ps =
                        c.prepareStatement(
                                "DELETE FROM ride WHERE tenant_id = ? AND email_message_id = ?")) {
            ps.setLong(1, tenantId());
            ps.setString(2, emailMessageId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Page<Ride> rides(RideQuery query, int page, int size) {
        try (Connection c = db.open()) {
            List<Object> params = new ArrayList<>();
            params.add(tenantId());
            StringBuilder rest = new StringBuilder();
            if (query.region().isPresent()) {
                rest.append(" AND region = ?");
                params.add(query.region().get());
            }
            if (query.currency().isPresent()) {
                rest.append(" AND currency = ?");
                params.add(query.currency().get());
            }
            if (query.provider().isPresent()) {
                rest.append(" AND provider = ? COLLATE NOCASE");
                params.add(query.provider().get());
            }
            if (query.from().isPresent()) {
                rest.append(" AND ride_date >= ?");
                params.add(query.from().get().toString());
            }
            if (query.to().isPresent()) {
                rest.append(" AND ride_date <= ?");
                params.add(query.to().get().toString());
            }
            long total;
            try (PreparedStatement ps =
                    c.prepareStatement("SELECT COUNT(*) FROM ride WHERE tenant_id = ?" + rest)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Ride> content = new ArrayList<>();
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "SELECT " + RIDE_COLS + " FROM ride WHERE tenant_id = ?" + rest
                                    + " ORDER BY ride_date ASC, ride_pk ASC LIMIT ? OFFSET ?")) {
                List<Object> pageParams = new ArrayList<>(params);
                pageParams.add((long) size);
                pageParams.add((long) page * size);
                bind(ps, pageParams);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        content.add(mapRide(rs));
                    }
                }
            }
            return new Page<>(content, page, size, total);
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private static boolean rideExists(Connection c, long tenant, String id) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT 1 FROM ride WHERE tenant_id = ? AND email_message_id = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Optional<Ride> readRide(Connection c, long tenant, String id) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT " + RIDE_COLS + " FROM ride WHERE tenant_id = ? AND email_message_id = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRide(rs)) : Optional.empty();
            }
        }
    }

    private static void insertRideRow(Connection c, long tenant, Ride r) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO ride (tenant_id, email_message_id, provider, region, ride_date,"
                            + " pickup_utc, dropoff_utc, pickup_addr, dropoff_addr, currency, fare_cents,"
                            + " tip_cents, payment_last4, rider, email_subject, email_received_utc, notes)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            ps.setLong(++i, tenant);
            ps.setString(++i, r.emailMessageId());
            ps.setString(++i, r.provider());
            ps.setString(++i, r.region().json());
            ps.setString(++i, r.rideDate().toString());
            ps.setString(++i, r.pickupUtc().map(Instant::toString).orElse(null));
            ps.setString(++i, r.dropoffUtc().map(Instant::toString).orElse(null));
            ps.setString(++i, r.pickupAddr().orElse(null));
            ps.setString(++i, r.dropoffAddr().orElse(null));
            ps.setString(++i, r.total().currency());
            ps.setLong(++i, r.fare().cents());
            ps.setLong(++i, r.tip().cents());
            ps.setString(++i, r.paymentLast4().orElse(null));
            ps.setString(++i, r.rider().orElse(null));
            ps.setString(++i, r.emailSubject());
            ps.setString(++i, r.emailReceivedUtc().map(Instant::toString).orElse(null));
            ps.setString(++i, r.notes().orElse(null));
            ps.executeUpdate();
        }
    }

    private static void updateRideRow(Connection c, long tenant, Ride r) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE ride SET provider = ?, region = ?, ride_date = ?, pickup_utc = ?,"
                            + " dropoff_utc = ?, pickup_addr = ?, dropoff_addr = ?, currency = ?,"
                            + " fare_cents = ?, tip_cents = ?, payment_last4 = ?, rider = ?,"
                            + " email_subject = ?, email_received_utc = ?, notes = ?, updated_at = " + NOW
                            + " WHERE tenant_id = ? AND email_message_id = ?")) {
            int i = 0;
            ps.setString(++i, r.provider());
            ps.setString(++i, r.region().json());
            ps.setString(++i, r.rideDate().toString());
            ps.setString(++i, r.pickupUtc().map(Instant::toString).orElse(null));
            ps.setString(++i, r.dropoffUtc().map(Instant::toString).orElse(null));
            ps.setString(++i, r.pickupAddr().orElse(null));
            ps.setString(++i, r.dropoffAddr().orElse(null));
            ps.setString(++i, r.total().currency());
            ps.setLong(++i, r.fare().cents());
            ps.setLong(++i, r.tip().cents());
            ps.setString(++i, r.paymentLast4().orElse(null));
            ps.setString(++i, r.rider().orElse(null));
            ps.setString(++i, r.emailSubject());
            ps.setString(++i, r.emailReceivedUtc().map(Instant::toString).orElse(null));
            ps.setString(++i, r.notes().orElse(null));
            ps.setLong(++i, tenant);
            ps.setString(++i, r.emailMessageId());
            ps.executeUpdate();
        }
    }

    private static Ride mapRide(ResultSet rs) throws SQLException {
        int i = 0;
        String id = rs.getString(++i);
        String provider = rs.getString(++i);
        Region region = Region.parse(rs.getString(++i));
        LocalDate rideDate = LocalDate.parse(rs.getString(++i));
        Optional<Instant> pickup = optInstant(rs.getString(++i));
        Optional<Instant> dropoff = optInstant(rs.getString(++i));
        Optional<String> pickupAddr = Optional.ofNullable(rs.getString(++i));
        Optional<String> dropoffAddr = Optional.ofNullable(rs.getString(++i));
        String currency = rs.getString(++i);
        Money fare = new Money(rs.getLong(++i), currency);
        Money tip = new Money(rs.getLong(++i), currency);
        Optional<String> last4 = Optional.ofNullable(rs.getString(++i));
        Optional<String> rider = Optional.ofNullable(rs.getString(++i));
        String subject = rs.getString(++i);
        Optional<Instant> received = optInstant(rs.getString(++i));
        Optional<String> notes = Optional.ofNullable(rs.getString(++i));
        String created = rs.getString(++i);
        String updated = rs.getString(++i);
        return new Ride(
                id, provider, region, rideDate, pickup, dropoff, pickupAddr, dropoffAddr, fare, tip,
                last4, rider, subject, received, notes, created, updated);
    }

    // ---- rentals ----

    @Override
    public Upserted<Rental> putRental(Rental rental) {
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenant = tenantId();
                        Long pk = rentalPk(conn, tenant, rental.company(), rental.confirmationNo());
                        boolean created = pk == null;
                        long rentalPk;
                        if (created) {
                            rentalPk = insertRentalRow(conn, tenant, rental);
                        } else {
                            updateRentalRow(conn, tenant, pk, rental);
                            rentalPk = pk;
                        }
                        replaceAdjustments(conn, tenant, rentalPk, rental.adjustments());
                        return new Upserted<>(
                                created,
                                readRental(conn, tenant, rental.company(), rental.confirmationNo())
                                        .orElseThrow());
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Optional<Rental> patchRental(String company, String confirmationNo, JsonNode patch) {
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenant = tenantId();
                        Optional<Rental> stored = readRental(conn, tenant, company, confirmationNo);
                        if (stored.isEmpty()) {
                            return Optional.<Rental>empty();
                        }
                        TenantSettings s = readSettings(conn, tenant).orElse(null);
                        String defCur = s == null ? "USD" : s.currency();
                        JsonNode merged = Inputs.mergedRental(stored.get(), patch);
                        // Keep the first-written company spelling on replace.
                        Rental updated =
                                Inputs.rentalFromPut(
                                        merged, stored.get().company(), stored.get().confirmationNo(), defCur);
                        Long pk = rentalPk(conn, tenant, company, confirmationNo);
                        updateRentalRow(conn, tenant, pk, updated);
                        replaceAdjustments(conn, tenant, pk, updated.adjustments());
                        return readRental(conn, tenant, company, confirmationNo);
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Optional<Rental> rental(String company, String confirmationNo) {
        try (Connection c = db.open()) {
            return readRental(c, tenantId(), company, confirmationNo);
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public boolean deleteRental(String company, String confirmationNo) {
        try (Connection c = db.open();
                PreparedStatement ps =
                        c.prepareStatement(
                                "DELETE FROM rental WHERE tenant_id = ? AND company = ?"
                                    + " AND confirmation_no = ?")) {
            ps.setLong(1, tenantId());
            ps.setString(2, company);
            ps.setString(3, confirmationNo);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Page<Rental> rentals(RentalQuery query, int page, int size) {
        try (Connection c = db.open()) {
            long tenant = tenantId();
            List<Object> params = new ArrayList<>();
            params.add(tenant);
            StringBuilder rest = new StringBuilder();
            if (query.purpose().isPresent()) {
                rest.append(" AND purpose = ?");
                params.add(query.purpose().get());
            }
            if (query.region().isPresent()) {
                rest.append(" AND region = ?");
                params.add(query.region().get());
            }
            if (query.currency().isPresent()) {
                rest.append(" AND currency = ?");
                params.add(query.currency().get());
            }
            if (query.company().isPresent()) {
                rest.append(" AND company = ?");
                params.add(query.company().get());
            }
            if (query.from().isPresent()) {
                rest.append(" AND start_date >= ?");
                params.add(query.from().get().toString());
            }
            if (query.to().isPresent()) {
                rest.append(" AND start_date <= ?");
                params.add(query.to().get().toString());
            }
            long total;
            try (PreparedStatement ps =
                    c.prepareStatement("SELECT COUNT(*) FROM rental WHERE tenant_id = ?" + rest)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Rental> content = new ArrayList<>();
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "SELECT rental_pk, " + RENTAL_COLS + " FROM rental WHERE tenant_id = ?"
                                            + rest
                                    + " ORDER BY start_date ASC, rental_pk ASC LIMIT ? OFFSET ?")) {
                List<Object> pageParams = new ArrayList<>(params);
                pageParams.add((long) size);
                pageParams.add((long) page * size);
                bind(ps, pageParams);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long pk = rs.getLong(1);
                        content.add(mapRental(rs, 2, readAdjustments(c, tenant, pk)));
                    }
                }
            }
            return new Page<>(content, page, size, total);
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private static Long rentalPk(Connection c, long tenant, String company, String confirmation)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT rental_pk FROM rental WHERE tenant_id = ? AND company = ?"
                            + " AND confirmation_no = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, company);
            ps.setString(3, confirmation);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static Optional<Rental> readRental(
            Connection c, long tenant, String company, String confirmation) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT rental_pk, " + RENTAL_COLS
                            + " FROM rental WHERE tenant_id = ? AND company = ? AND confirmation_no = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, company);
            ps.setString(3, confirmation);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long pk = rs.getLong(1);
                return Optional.of(mapRental(rs, 2, readAdjustments(c, tenant, pk)));
            }
        }
    }

    private static long insertRentalRow(Connection c, long tenant, Rental r) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO rental (tenant_id, company, confirmation_no, booking_channel,"
                            + " start_date, end_date, location, purpose, region, currency, gross_cents,"
                            + " payment_last4, email_message_id, email_subject, notes)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
            int i = 0;
            ps.setLong(++i, tenant);
            ps.setString(++i, r.company());
            ps.setString(++i, r.confirmationNo());
            ps.setString(++i, r.bookingChannel().orElse(null));
            ps.setString(++i, r.startDate().toString());
            ps.setString(++i, r.endDate().toString());
            ps.setString(++i, r.location().orElse(null));
            ps.setString(++i, r.purpose().json());
            ps.setString(++i, r.region().json());
            ps.setString(++i, r.gross().currency());
            ps.setLong(++i, r.gross().cents());
            ps.setString(++i, r.paymentLast4().orElse(null));
            ps.setString(++i, r.emailMessageId().orElse(null));
            ps.setString(++i, r.emailSubject().orElse(null));
            ps.setString(++i, r.notes().orElse(null));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void updateRentalRow(Connection c, long tenant, long pk, Rental r)
            throws SQLException {
        // Company keeps its first-written spelling; the path key matched case-insensitively.
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE rental SET booking_channel = ?, start_date = ?, end_date = ?, location = ?,"
                            + " purpose = ?, region = ?, currency = ?, gross_cents = ?, payment_last4 = ?,"
                            + " email_message_id = ?, email_subject = ?, notes = ?, updated_at = " + NOW
                            + " WHERE tenant_id = ? AND rental_pk = ?")) {
            int i = 0;
            ps.setString(++i, r.bookingChannel().orElse(null));
            ps.setString(++i, r.startDate().toString());
            ps.setString(++i, r.endDate().toString());
            ps.setString(++i, r.location().orElse(null));
            ps.setString(++i, r.purpose().json());
            ps.setString(++i, r.region().json());
            ps.setString(++i, r.gross().currency());
            ps.setLong(++i, r.gross().cents());
            ps.setString(++i, r.paymentLast4().orElse(null));
            ps.setString(++i, r.emailMessageId().orElse(null));
            ps.setString(++i, r.emailSubject().orElse(null));
            ps.setString(++i, r.notes().orElse(null));
            ps.setLong(++i, tenant);
            ps.setLong(++i, pk);
            ps.executeUpdate();
        }
    }

    private static void replaceAdjustments(
            Connection c, long tenant, long rentalPk, List<Adjustment> adjustments) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM rental_adjustment WHERE rental_pk = ? AND EXISTS (SELECT 1 FROM rental r"
                            + " WHERE r.rental_pk = rental_adjustment.rental_pk AND r.tenant_id = ?)")) {
            ps.setLong(1, rentalPk);
            ps.setLong(2, tenant);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO rental_adjustment (rental_pk, line_no, kind, amount_cents, affects_net,"
                            + " effective_date, hold_released, email_message_id, notes)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Adjustment a : adjustments) {
                int i = 0;
                ps.setLong(++i, rentalPk);
                ps.setInt(++i, a.lineNo());
                ps.setString(++i, a.kind());
                ps.setLong(++i, a.amountCents());
                ps.setInt(++i, a.affectsNet() ? 1 : 0);
                ps.setString(++i, a.effectiveDate().toString());
                if (a instanceof Adjustment.Hold h) {
                    ps.setInt(++i, h.released() ? 1 : 0);
                } else {
                    ps.setObject(++i, null);
                }
                ps.setString(++i, a.emailMessageId().orElse(null));
                ps.setString(++i, a.notes().orElse(null));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static List<Adjustment> readAdjustments(Connection c, long tenant, long rentalPk)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT a.line_no, a.kind, a.amount_cents, a.effective_date, a.hold_released,"
                            + " a.email_message_id, a.notes FROM rental_adjustment a"
                            + " WHERE a.rental_pk = ? AND EXISTS (SELECT 1 FROM rental r"
                            + " WHERE r.rental_pk = a.rental_pk AND r.tenant_id = ?)"
                            + " ORDER BY a.line_no")) {
            ps.setLong(1, rentalPk);
            ps.setLong(2, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                List<Adjustment> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapAdjustment(rs));
                }
                return out;
            }
        }
    }

    private static Adjustment mapAdjustment(ResultSet rs) throws SQLException {
        int lineNo = rs.getInt(1);
        String kind = rs.getString(2);
        long amount = rs.getLong(3);
        LocalDate effective = LocalDate.parse(rs.getString(4));
        Object released = rs.getObject(5);
        Optional<String> email = Optional.ofNullable(rs.getString(6));
        Optional<String> notes = Optional.ofNullable(rs.getString(7));
        return switch (kind) {
            case "rebate" -> new Adjustment.Rebate(lineNo, amount, effective, email, notes);
            case "refund" -> new Adjustment.Refund(lineNo, amount, effective, email, notes);
            case "reimbursement" -> new Adjustment.Reimbursement(lineNo, amount, effective, email, notes);
            case "hold" ->
                new Adjustment.Hold(
                        lineNo, amount, effective, released != null && ((Number) released).intValue() == 1,
                        email, notes);
            default -> throw new StoreException("unknown adjustment kind: " + kind, null);
        };
    }

    private static Rental mapRental(ResultSet rs, int offset, List<Adjustment> adjustments)
            throws SQLException {
        int i = offset - 1;
        String company = rs.getString(++i);
        String confirmation = rs.getString(++i);
        Optional<String> channel = Optional.ofNullable(rs.getString(++i));
        LocalDate start = LocalDate.parse(rs.getString(++i));
        LocalDate end = LocalDate.parse(rs.getString(++i));
        Optional<String> location = Optional.ofNullable(rs.getString(++i));
        Purpose purpose = Purpose.parse(rs.getString(++i));
        Region region = Region.parse(rs.getString(++i));
        String currency = rs.getString(++i);
        Money gross = new Money(rs.getLong(++i), currency);
        Optional<String> last4 = Optional.ofNullable(rs.getString(++i));
        Optional<String> emailId = Optional.ofNullable(rs.getString(++i));
        Optional<String> subject = Optional.ofNullable(rs.getString(++i));
        Optional<String> notes = Optional.ofNullable(rs.getString(++i));
        String created = rs.getString(++i);
        String updated = rs.getString(++i);
        return new Rental(
                company, confirmation, channel, start, end, location, purpose, region, gross,
                adjustments, last4, emailId, subject, notes, created, updated);
    }

    // ---- recurring costs ----

    @Override
    public Upserted<RecurringCost> putRecurringCost(RecurringCost cost) {
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenant = tenantId();
                        checkOverlap(
                                conn, tenant, cost.name(), cost.effectiveFrom(),
                                cost.effectiveTo().orElse(null));
                        boolean exists =
                                readCost(conn, tenant, cost.name(), cost.effectiveFrom()).isPresent();
                        if (exists) {
                            updateCostRow(conn, tenant, cost);
                        } else {
                            insertCostRow(conn, tenant, cost);
                        }
                        return new Upserted<>(
                                !exists,
                                readCost(conn, tenant, cost.name(), cost.effectiveFrom()).orElseThrow());
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Optional<RecurringCost> patchRecurringCost(String name, LocalDate from, JsonNode patch) {
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenant = tenantId();
                        Optional<RecurringCost> stored = readCost(conn, tenant, name, from);
                        if (stored.isEmpty()) {
                            return Optional.<RecurringCost>empty();
                        }
                        JsonNode merged = Inputs.mergedCost(stored.get(), patch);
                        RecurringCost updated = Inputs.costFromPut(merged, name, from);
                        checkOverlap(
                                conn, tenant, name, from, updated.effectiveTo().orElse(null));
                        updateCostRow(conn, tenant, updated);
                        return readCost(conn, tenant, name, from);
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Optional<RecurringCost> recurringCost(String name, LocalDate from) {
        try (Connection c = db.open()) {
            return readCost(c, tenantId(), name, from);
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public boolean deleteRecurringCost(String name, LocalDate from) {
        try (Connection c = db.open();
                PreparedStatement ps =
                        c.prepareStatement(
                                "DELETE FROM recurring_cost WHERE tenant_id = ? AND name = ?"
                                    + " AND effective_from = ?")) {
            ps.setLong(1, tenantId());
            ps.setString(2, name);
            ps.setString(3, from.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public List<RecurringCost> recurringCosts(Optional<Side> side, Optional<LocalDate> activeOn) {
        try (Connection c = db.open()) {
            StringBuilder rest = new StringBuilder();
            List<Object> params = new ArrayList<>();
            params.add(tenantId());
            if (side.isPresent()) {
                rest.append(" AND side = ?");
                params.add(side.get().json());
            }
            if (activeOn.isPresent()) {
                rest.append(" AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)");
                params.add(activeOn.get().toString());
                params.add(activeOn.get().toString());
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "SELECT " + COST_COLS + " FROM recurring_cost WHERE tenant_id = ?" + rest
                                    + " ORDER BY name ASC, effective_from ASC")) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<RecurringCost> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapCost(rs));
                    }
                    return out;
                }
            }
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    /** Rejects ranges overlapping another row with the same name. SQLite cannot express this. */
    private static void checkOverlap(
            Connection c, long tenant, String name, LocalDate from, LocalDate to) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT effective_from, effective_to FROM recurring_cost"
                            + " WHERE tenant_id = ? AND name = ? AND effective_from <> ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, from.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate otherFrom = LocalDate.parse(rs.getString(1));
                    String otherToRaw = rs.getString(2);
                    LocalDate otherTo = otherToRaw == null ? null : LocalDate.parse(otherToRaw);
                    if (rangesOverlap(from, to, otherFrom, otherTo)) {
                        throw new Conflict("recurring cost overlaps an existing range for this name");
                    }
                }
            }
        }
    }

    private static boolean rangesOverlap(LocalDate from, LocalDate to, LocalDate otherFrom, LocalDate otherTo) {
        LocalDate end = to == null ? LocalDate.MAX : to;
        LocalDate otherEnd = otherTo == null ? LocalDate.MAX : otherTo;
        return !from.isAfter(otherEnd) && !otherFrom.isAfter(end);
    }

    private static Optional<RecurringCost> readCost(Connection c, long tenant, String name, LocalDate from)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT " + COST_COLS
                            + " FROM recurring_cost WHERE tenant_id = ? AND name = ?"
                            + " AND effective_from = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, from.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCost(rs)) : Optional.empty();
            }
        }
    }

    private static void insertCostRow(Connection c, long tenant, RecurringCost cost) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO recurring_cost (tenant_id, name, side, period, amount_cents,"
                            + " effective_from, effective_to, notes)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            ps.setLong(++i, tenant);
            ps.setString(++i, cost.name());
            ps.setString(++i, cost.side().json());
            ps.setString(++i, cost.period().json());
            ps.setLong(++i, cost.amountCents());
            ps.setString(++i, cost.effectiveFrom().toString());
            ps.setString(++i, cost.effectiveTo().map(LocalDate::toString).orElse(null));
            ps.setString(++i, cost.notes().orElse(null));
            ps.executeUpdate();
        }
    }

    private static void updateCostRow(Connection c, long tenant, RecurringCost cost) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE recurring_cost SET side = ?, period = ?, amount_cents = ?, effective_to = ?,"
                            + " notes = ?, updated_at = " + NOW
                            + " WHERE tenant_id = ? AND name = ? AND effective_from = ?")) {
            int i = 0;
            ps.setString(++i, cost.side().json());
            ps.setString(++i, cost.period().json());
            ps.setLong(++i, cost.amountCents());
            ps.setString(++i, cost.effectiveTo().map(LocalDate::toString).orElse(null));
            ps.setString(++i, cost.notes().orElse(null));
            ps.setLong(++i, tenant);
            ps.setString(++i, cost.name());
            ps.setString(++i, cost.effectiveFrom().toString());
            ps.executeUpdate();
        }
    }

    private static RecurringCost mapCost(ResultSet rs) throws SQLException {
        int i = 0;
        String name = rs.getString(++i);
        LocalDate from = LocalDate.parse(rs.getString(++i));
        String toRaw = rs.getString(++i);
        Optional<LocalDate> to = toRaw == null ? Optional.empty() : Optional.of(LocalDate.parse(toRaw));
        Side side = Side.parse(rs.getString(++i));
        Period period = Period.parse(rs.getString(++i));
        long amount = rs.getLong(++i);
        Optional<String> notes = Optional.ofNullable(rs.getString(++i));
        String created = rs.getString(++i);
        String updated = rs.getString(++i);
        return new RecurringCost(name, from, to, side, period, amount, notes, created, updated);
    }

    // ---- settings ----

    @Override
    public Optional<TenantSettings> settings() {
        try (Connection c = db.open()) {
            return readSettings(c, tenantId());
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public Upserted<TenantSettings> putSettings(TenantSettings settings) {
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenant = tenantId();
                        boolean exists = readSettings(conn, tenant).isPresent();
                        if (exists) {
                            try (PreparedStatement ps =
                                    conn.prepareStatement(
                                            "UPDATE tenant_settings SET comparison_start = ?, currency = ?,"
                                                + " home_region = ?, personal_tag = ?, time_zone = ?,"
                                                + " updated_at = " + NOW + " WHERE tenant_id = ?")) {
                                int i = 0;
                                ps.setString(++i, settings.comparisonStart().toString());
                                ps.setString(++i, settings.currency());
                                ps.setString(++i, settings.homeRegion());
                                ps.setString(++i, settings.personalTag());
                                ps.setString(++i, settings.timeZone());
                                ps.setLong(++i, tenant);
                                ps.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement ps =
                                    conn.prepareStatement(
                                            "INSERT INTO tenant_settings (tenant_id, comparison_start, currency,"
                                                + " home_region, personal_tag, time_zone)"
                                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
                                int i = 0;
                                ps.setLong(++i, tenant);
                                ps.setString(++i, settings.comparisonStart().toString());
                                ps.setString(++i, settings.currency());
                                ps.setString(++i, settings.homeRegion());
                                ps.setString(++i, settings.personalTag());
                                ps.setString(++i, settings.timeZone());
                                ps.executeUpdate();
                            }
                        }
                        return new Upserted<>(!exists, readSettings(conn, tenant).orElseThrow());
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private static Optional<TenantSettings> readSettings(Connection c, long tenant) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT comparison_start, currency, home_region, personal_tag, time_zone,"
                            + " created_at, updated_at FROM tenant_settings WHERE tenant_id = ?")) {
            ps.setLong(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new TenantSettings(
                                LocalDate.parse(rs.getString(1)),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7)));
            }
        }
    }

    // ---- comparison ----

    @Override
    public ComparisonReport comparison(LocalDate asOfOrNull) {
        try (Connection c = db.open()) {
            long tenant = tenantId();
            TenantSettings s =
                    readSettings(c, tenant)
                            .orElseThrow(() -> new Conflict("settings not configured"));
            LocalDate asOf =
                    asOfOrNull != null ? asOfOrNull : LocalDate.now(ZoneId.of(s.timeZone()));
            List<MonthlyComparison> rows = ComparisonQuery.run(c, tenant, asOf);
            List<ComparisonReport.CumulativeMonth> months = new ArrayList<>();
            long cumOld = 0;
            long cumNew = 0;
            long cumSaved = 0;
            int days = 0;
            int rideCount = 0;
            int rentalCount = 0;
            int excludedRides = 0;
            int excludedRentals = 0;
            for (MonthlyComparison m : rows) {
                cumOld += m.oldCents();
                cumNew += m.newCents();
                cumSaved += m.savedCents();
                days += m.days();
                rideCount += m.rideCount();
                rentalCount += m.rentalCount();
                excludedRides += m.excludedRides();
                excludedRentals += m.excludedRentals();
                months.add(new ComparisonReport.CumulativeMonth(m, cumOld, cumNew, cumSaved));
            }
            return new ComparisonReport(
                    s.currency(),
                    s.comparisonStart(),
                    asOf,
                    months,
                    new ComparisonReport.Totals(
                            days, cumOld, cumNew, cumSaved, rideCount, rentalCount, excludedRides,
                            excludedRentals));
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    // ---- helpers ----

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (p instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, (String) p);
            }
        }
    }

    private static Optional<Instant> optInstant(String raw) {
        return raw == null ? Optional.empty() : Optional.of(Instant.parse(raw));
    }
}
