package org.miosys.transport.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.miosys.transport.domain.Adjustment;
import org.miosys.transport.domain.ComparisonReport;
import org.miosys.transport.domain.MonthlyComparison;
import org.miosys.transport.domain.Page;
import org.miosys.transport.domain.RecurringCost;
import org.miosys.transport.domain.Rental;
import org.miosys.transport.domain.Ride;
import org.miosys.transport.domain.TenantSettings;
import org.miosys.transport.store.TenantAdmin.KeyView;
import org.miosys.transport.store.TenantAdmin.TenantView;

/** Renders domain objects. Every documented field is always present; absent values are null. */
public final class Views {

    private Views() {}

    public static void money(ObjectNode o, String field, long cents) {
        o.put(field, BigDecimal.valueOf(cents, 2));
    }

    private static void optStr(ObjectNode o, String field, Optional<String> v) {
        if (v.isPresent()) {
            o.put(field, v.get());
        } else {
            o.putNull(field);
        }
    }

    public static ObjectNode ride(Ride r) {
        ObjectNode o = Json.object();
        o.put("emailMessageId", r.emailMessageId());
        o.put("provider", r.provider());
        o.put("region", r.region().json());
        o.put("rideDate", r.rideDate().toString());
        if (r.pickupUtc().isPresent()) {
            o.put("pickupUtc", r.pickupUtc().get().toString());
        } else {
            o.putNull("pickupUtc");
        }
        if (r.dropoffUtc().isPresent()) {
            o.put("dropoffUtc", r.dropoffUtc().get().toString());
        } else {
            o.putNull("dropoffUtc");
        }
        optStr(o, "pickupAddr", r.pickupAddr());
        optStr(o, "dropoffAddr", r.dropoffAddr());
        o.put("currency", r.total().currency());
        money(o, "fare", r.fare().cents());
        money(o, "tip", r.tip().cents());
        money(o, "total", r.total().cents());
        optStr(o, "paymentLast4", r.paymentLast4());
        optStr(o, "rider", r.rider());
        o.put("emailSubject", r.emailSubject());
        if (r.emailReceivedUtc().isPresent()) {
            o.put("emailReceivedUtc", r.emailReceivedUtc().get().toString());
        } else {
            o.putNull("emailReceivedUtc");
        }
        optStr(o, "notes", r.notes());
        o.put("createdAt", r.createdAt());
        o.put("updatedAt", r.updatedAt());
        return o;
    }

    public static ObjectNode adjustment(Adjustment a) {
        ObjectNode o = Json.object();
        o.put("lineNo", a.lineNo());
        o.put("kind", a.kind());
        money(o, "amount", a.amountCents());
        o.put("affectsNet", a.affectsNet());
        o.put("effectiveDate", a.effectiveDate().toString());
        if (a instanceof Adjustment.Hold h) {
            o.put("holdReleased", h.released());
        } else {
            o.putNull("holdReleased");
        }
        optStr(o, "emailMessageId", a.emailMessageId());
        optStr(o, "notes", a.notes());
        return o;
    }

    public static ObjectNode rental(Rental r) {
        ObjectNode o = Json.object();
        o.put("company", r.company());
        o.put("confirmationNo", r.confirmationNo());
        optStr(o, "bookingChannel", r.bookingChannel());
        o.put("startDate", r.startDate().toString());
        o.put("endDate", r.endDate().toString());
        optStr(o, "location", r.location());
        o.put("purpose", r.purpose().json());
        o.put("region", r.region().json());
        o.put("currency", r.gross().currency());
        money(o, "gross", r.gross().cents());
        money(o, "net", r.net().cents());
        optStr(o, "paymentLast4", r.paymentLast4());
        optStr(o, "emailMessageId", r.emailMessageId());
        optStr(o, "emailSubject", r.emailSubject());
        optStr(o, "notes", r.notes());
        List<Adjustment> sorted = new ArrayList<>(r.adjustments());
        sorted.sort(Comparator.comparingInt(Adjustment::lineNo));
        ArrayNode arr = Json.mapper().createArrayNode();
        for (Adjustment a : sorted) {
            arr.add(adjustment(a));
        }
        o.set("adjustments", arr);
        o.put("createdAt", r.createdAt());
        o.put("updatedAt", r.updatedAt());
        return o;
    }

    public static ObjectNode cost(RecurringCost c) {
        ObjectNode o = Json.object();
        o.put("name", c.name());
        o.put("effectiveFrom", c.effectiveFrom().toString());
        if (c.effectiveTo().isPresent()) {
            o.put("effectiveTo", c.effectiveTo().get().toString());
        } else {
            o.putNull("effectiveTo");
        }
        o.put("side", c.side().json());
        o.put("period", c.period().json());
        money(o, "amount", c.amountCents());
        optStr(o, "notes", c.notes());
        o.put("createdAt", c.createdAt());
        o.put("updatedAt", c.updatedAt());
        return o;
    }

    public static ObjectNode settings(TenantSettings s) {
        ObjectNode o = Json.object();
        o.put("comparisonStart", s.comparisonStart().toString());
        o.put("currency", s.currency());
        o.put("homeRegion", s.homeRegion());
        o.put("personalTag", s.personalTag());
        o.put("timeZone", s.timeZone());
        o.put("createdAt", s.createdAt());
        o.put("updatedAt", s.updatedAt());
        return o;
    }

    public static <T> ObjectNode page(Page<T> p, Function<T, ObjectNode> render) {
        ObjectNode o = Json.object();
        ArrayNode arr = Json.mapper().createArrayNode();
        for (T item : p.content()) {
            arr.add(render.apply(item));
        }
        o.set("content", arr);
        o.put("page", p.page());
        o.put("size", p.size());
        o.put("total", p.total());
        return o;
    }

    public static ObjectNode comparison(ComparisonReport r) {
        ObjectNode o = Json.object();
        o.put("currency", r.currency());
        o.put("comparisonStart", r.comparisonStart().toString());
        o.put("asOf", r.asOf().toString());
        ArrayNode months = Json.mapper().createArrayNode();
        for (ComparisonReport.CumulativeMonth cm : r.months()) {
            MonthlyComparison m = cm.month();
            ObjectNode mo = Json.object();
            mo.put("month", m.month().toString());
            mo.put("windowFrom", m.windowFrom().toString());
            mo.put("windowTo", m.windowTo().toString());
            mo.put("days", m.days());
            money(mo, "oldCost", m.oldCents());
            money(mo, "rides", m.rideCents());
            mo.put("rideCount", m.rideCount());
            money(mo, "rentals", m.rentalCents());
            mo.put("rentalCount", m.rentalCount());
            money(mo, "recurringNew", m.recurringNewCents());
            money(mo, "newCost", m.newCents());
            money(mo, "saved", m.savedCents());
            mo.put("excludedRides", m.excludedRides());
            mo.put("excludedRentals", m.excludedRentals());
            money(mo, "cumulativeOldCost", cm.cumulativeOld());
            money(mo, "cumulativeNewCost", cm.cumulativeNew());
            money(mo, "cumulativeSaved", cm.cumulativeSaved());
            months.add(mo);
        }
        o.set("months", months);
        ComparisonReport.Totals t = r.totals();
        ObjectNode totals = Json.object();
        totals.put("days", t.days());
        money(totals, "oldCost", t.oldCents());
        money(totals, "newCost", t.newCents());
        money(totals, "saved", t.savedCents());
        totals.put("rideCount", t.rideCount());
        totals.put("rentalCount", t.rentalCount());
        totals.put("excludedRides", t.excludedRides());
        totals.put("excludedRentals", t.excludedRentals());
        o.set("totals", totals);
        return o;
    }

    public static ObjectNode tenant(TenantView t) {
        ObjectNode o = Json.object();
        o.put("tenantId", t.tenantId());
        o.put("name", t.name());
        o.put("isOperator", t.operator());
        o.put("isEnabled", t.enabled());
        o.put("createdAt", t.createdAt());
        o.put("updatedAt", t.updatedAt());
        ArrayNode keys = Json.mapper().createArrayNode();
        for (KeyView k : t.keys()) {
            ObjectNode ko = Json.object();
            ko.put("keyId", k.keyId());
            ko.put("label", k.label());
            ko.put("isEnabled", k.enabled());
            ko.put("createdAt", k.createdAt());
            ko.put("updatedAt", k.updatedAt());
            keys.add(ko);
        }
        o.set("keys", keys);
        return o;
    }
}
