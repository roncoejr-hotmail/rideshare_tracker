package org.miosys.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.miosys.transport.domain.ComparisonReport;
import org.miosys.transport.domain.Enums.Side;
import org.miosys.transport.domain.Page;
import org.miosys.transport.domain.RecurringCost;
import org.miosys.transport.domain.Rental;
import org.miosys.transport.domain.Ride;
import org.miosys.transport.domain.TenantSettings;
import org.miosys.transport.http.ApiException.BadRequest;
import org.miosys.transport.http.ApiException.NotFound;
import org.miosys.transport.store.KeySelfService;
import org.miosys.transport.store.TenantAdmin;
import org.miosys.transport.store.TenantAdmin.IssuedKey;
import org.miosys.transport.store.TenantAdmin.IssuedTenant;
import org.miosys.transport.store.TenantAdmin.TenantView;
import org.miosys.transport.store.TransportStore;
import org.miosys.transport.store.TransportStore.RentalQuery;
import org.miosys.transport.store.TransportStore.RideQuery;
import org.miosys.transport.store.Upserted;

/** Registers every route with its handler. Body first, then path and field validation. */
public final class Routes {

    private Routes() {}

    public static void register(Router router, TransportStore store, TenantAdmin admin, KeySelfService self) {
        router.add("GET", "/health", (ex, pp, q) -> Json.send(ex, 200, Json.object().put("status", "ok")));

        router.add(
                "POST",
                "/api/bootstrap",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String name = Validator.reqString(body, "name", 1, 80);
                    String keyLabel = Validator.optString(body, "keyLabel", 1, 40).orElse("default");
                    IssuedTenant t = admin.bootstrap(name, keyLabel);
                    Json.send(ex, 201, issuedTenant(t));
                });

        // ---- rides ----
        router.add(
                "PUT",
                "/api/rides/{emailMessageId}",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String id = Validator.pathString("emailMessageId", pp.get("emailMessageId"), 1, 512);
                    TenantSettings s = store.settings().orElse(null);
                    Ride ride =
                            Inputs.rideFromPut(
                                    body,
                                    id,
                                    s == null ? "USD" : s.currency(),
                                    s == null ? "[Personal]" : s.personalTag(),
                                    true);
                    Upserted<Ride> up = store.putRide(ride);
                    Json.send(ex, up.created() ? 201 : 200, Views.ride(up.value()));
                });
        router.add(
                "PATCH",
                "/api/rides/{emailMessageId}",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String id = Validator.pathString("emailMessageId", pp.get("emailMessageId"), 1, 512);
                    Ride ride = store.patchRide(id, body).orElseThrow(NotFound::new);
                    Json.send(ex, 200, Views.ride(ride));
                });
        router.add(
                "GET",
                "/api/rides/{emailMessageId}",
                (ex, pp, q) -> {
                    String id = Validator.pathString("emailMessageId", pp.get("emailMessageId"), 1, 512);
                    Ride ride = store.ride(id).orElseThrow(NotFound::new);
                    Json.send(ex, 200, Views.ride(ride));
                });
        router.add(
                "DELETE",
                "/api/rides/{emailMessageId}",
                (ex, pp, q) -> {
                    String id = Validator.pathString("emailMessageId", pp.get("emailMessageId"), 1, 512);
                    if (!store.deleteRide(id)) {
                        throw new NotFound();
                    }
                    Json.sendEmpty(ex, 204);
                });
        router.add(
                "GET",
                "/api/rides",
                (ex, pp, q) -> {
                    Optional<String> region =
                            q.opt("region").map(v -> Validator.parseEnum("region", v, "local", "travel"));
                    Optional<String> currency =
                            q.opt("currency").map(v -> Validator.parseCurrency("currency", v));
                    Optional<String> provider = q.opt("provider");
                    Optional<LocalDate> from = q.opt("from").map(v -> Validator.parseDate("from", v));
                    Optional<LocalDate> to = q.opt("to").map(v -> Validator.parseDate("to", v));
                    if (from.isPresent() && to.isPresent() && from.get().isAfter(to.get())) {
                        throw new BadRequest("from must not be after to");
                    }
                    Page<Ride> page =
                            store.rides(
                                    new RideQuery(region, currency, provider, from, to),
                                    Validator.queryPage(q),
                                    Validator.querySize(q));
                    Json.send(ex, 200, Views.page(page, Views::ride));
                });

        // ---- rentals ----
        router.add(
                "PUT",
                "/api/rentals/{company}/{confirmationNo}",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String company = Validator.pathString("company", pp.get("company"), 1, 40);
                    String confirmation =
                            Validator.pathString("confirmationNo", pp.get("confirmationNo"), 1, 64);
                    TenantSettings s = store.settings().orElse(null);
                    Rental rental =
                            Inputs.rentalFromPut(
                                    body, company, confirmation, s == null ? "USD" : s.currency());
                    Upserted<Rental> up = store.putRental(rental);
                    Json.send(ex, up.created() ? 201 : 200, Views.rental(up.value()));
                });
        router.add(
                "PATCH",
                "/api/rentals/{company}/{confirmationNo}",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String company = Validator.pathString("company", pp.get("company"), 1, 40);
                    String confirmation =
                            Validator.pathString("confirmationNo", pp.get("confirmationNo"), 1, 64);
                    Rental rental =
                            store.patchRental(company, confirmation, body).orElseThrow(NotFound::new);
                    Json.send(ex, 200, Views.rental(rental));
                });
        router.add(
                "GET",
                "/api/rentals/{company}/{confirmationNo}",
                (ex, pp, q) -> {
                    String company = Validator.pathString("company", pp.get("company"), 1, 40);
                    String confirmation =
                            Validator.pathString("confirmationNo", pp.get("confirmationNo"), 1, 64);
                    Rental rental = store.rental(company, confirmation).orElseThrow(NotFound::new);
                    Json.send(ex, 200, Views.rental(rental));
                });
        router.add(
                "DELETE",
                "/api/rentals/{company}/{confirmationNo}",
                (ex, pp, q) -> {
                    String company = Validator.pathString("company", pp.get("company"), 1, 40);
                    String confirmation =
                            Validator.pathString("confirmationNo", pp.get("confirmationNo"), 1, 64);
                    if (!store.deleteRental(company, confirmation)) {
                        throw new NotFound();
                    }
                    Json.sendEmpty(ex, 204);
                });
        router.add(
                "GET",
                "/api/rentals",
                (ex, pp, q) -> {
                    Optional<String> purpose =
                            q.opt("purpose").map(v -> Validator.parseEnum("purpose", v, "personal", "business"));
                    Optional<String> region =
                            q.opt("region").map(v -> Validator.parseEnum("region", v, "local", "travel"));
                    Optional<String> currency =
                            q.opt("currency").map(v -> Validator.parseCurrency("currency", v));
                    Optional<String> company = q.opt("company");
                    Optional<LocalDate> from = q.opt("from").map(v -> Validator.parseDate("from", v));
                    Optional<LocalDate> to = q.opt("to").map(v -> Validator.parseDate("to", v));
                    if (from.isPresent() && to.isPresent() && from.get().isAfter(to.get())) {
                        throw new BadRequest("from must not be after to");
                    }
                    Page<Rental> page =
                            store.rentals(
                                    new RentalQuery(purpose, region, currency, company, from, to),
                                    Validator.queryPage(q),
                                    Validator.querySize(q));
                    Json.send(ex, 200, Views.page(page, Views::rental));
                });

        // ---- recurring costs ----
        router.add(
                "PUT",
                "/api/recurring-costs/{name}/{effectiveFrom}",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String name = Validator.pathString("name", pp.get("name"), 1, 60);
                    LocalDate from = Validator.parseDate("effectiveFrom", pp.get("effectiveFrom"));
                    RecurringCost cost = Inputs.costFromPut(body, name, from);
                    Upserted<RecurringCost> up = store.putRecurringCost(cost);
                    Json.send(ex, up.created() ? 201 : 200, Views.cost(up.value()));
                });
        router.add(
                "PATCH",
                "/api/recurring-costs/{name}/{effectiveFrom}",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String name = Validator.pathString("name", pp.get("name"), 1, 60);
                    LocalDate from = Validator.parseDate("effectiveFrom", pp.get("effectiveFrom"));
                    RecurringCost cost =
                            store.patchRecurringCost(name, from, body).orElseThrow(NotFound::new);
                    Json.send(ex, 200, Views.cost(cost));
                });
        router.add(
                "GET",
                "/api/recurring-costs/{name}/{effectiveFrom}",
                (ex, pp, q) -> {
                    String name = Validator.pathString("name", pp.get("name"), 1, 60);
                    LocalDate from = Validator.parseDate("effectiveFrom", pp.get("effectiveFrom"));
                    RecurringCost cost = store.recurringCost(name, from).orElseThrow(NotFound::new);
                    Json.send(ex, 200, Views.cost(cost));
                });
        router.add(
                "DELETE",
                "/api/recurring-costs/{name}/{effectiveFrom}",
                (ex, pp, q) -> {
                    String name = Validator.pathString("name", pp.get("name"), 1, 60);
                    LocalDate from = Validator.parseDate("effectiveFrom", pp.get("effectiveFrom"));
                    if (!store.deleteRecurringCost(name, from)) {
                        throw new NotFound();
                    }
                    Json.sendEmpty(ex, 204);
                });
        router.add(
                "GET",
                "/api/recurring-costs",
                (ex, pp, q) -> {
                    Optional<Side> side =
                            q.opt("side")
                                    .map(v -> Side.parse(Validator.parseEnum("side", v, "baseline", "current")));
                    Optional<LocalDate> activeOn =
                            q.opt("activeOn").map(v -> Validator.parseDate("activeOn", v));
                    ArrayNode arr = Json.mapper().createArrayNode();
                    for (RecurringCost c : store.recurringCosts(side, activeOn)) {
                        arr.add(Views.cost(c));
                    }
                    Json.send(ex, 200, arr);
                });

        // ---- settings ----
        router.add(
                "GET",
                "/api/settings",
                (ex, pp, q) -> {
                    TenantSettings s = store.settings().orElseThrow(NotFound::new);
                    Json.send(ex, 200, Views.settings(s));
                });
        router.add(
                "PUT",
                "/api/settings",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    Upserted<TenantSettings> up = store.putSettings(Inputs.settingsFromPut(body));
                    Json.send(ex, up.created() ? 201 : 200, Views.settings(up.value()));
                });

        // ---- comparison ----
        router.add(
                "GET",
                "/api/comparison",
                (ex, pp, q) -> {
                    LocalDate asOf = q.opt("asOf").map(v -> Validator.parseDate("asOf", v)).orElse(null);
                    ComparisonReport report = store.comparison(asOf);
                    Json.send(ex, 200, Views.comparison(report));
                });

        // ---- tenant administration (operator only) ----
        router.add(
                "POST",
                "/api/admin/tenants",
                true,
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    String name = Validator.reqString(body, "name", 1, 80);
                    String keyLabel = Validator.optString(body, "keyLabel", 1, 40).orElse("default");
                    IssuedTenant t = admin.createTenant(name, keyLabel);
                    Json.send(ex, 201, issuedTenant(t));
                });
        router.add(
                "GET",
                "/api/admin/tenants",
                true,
                (ex, pp, q) -> {
                    ArrayNode arr = Json.mapper().createArrayNode();
                    for (TenantView t : admin.tenants()) {
                        arr.add(Views.tenant(t));
                    }
                    Json.send(ex, 200, arr);
                });
        router.add(
                "PATCH",
                "/api/admin/tenants/{tenantId}",
                true,
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    long tenantId = pathId(pp.get("tenantId"));
                    Optional<String> name = Validator.optString(body, "name", 1, 80);
                    Optional<Boolean> enabled = Validator.optBool(body, "isEnabled");
                    TenantView t = admin.updateTenant(tenantId, name, enabled);
                    Json.send(ex, 200, Views.tenant(t));
                });
        router.add(
                "POST",
                "/api/admin/tenants/{tenantId}/keys",
                true,
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    long tenantId = pathId(pp.get("tenantId"));
                    String label = Validator.reqString(body, "label", 1, 40);
                    IssuedKey k = admin.issueKey(tenantId, label);
                    ObjectNode out = Json.object();
                    out.put("keyId", k.keyId());
                    out.put("tenantId", k.tenantId());
                    out.put("label", k.label());
                    out.put("apiKey", k.apiKey());
                    Json.send(ex, 201, out);
                });
        router.add(
                "PATCH",
                "/api/admin/keys/{keyId}",
                true,
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    long keyId = pathId(pp.get("keyId"));
                    boolean enabled = Validator.reqBool(body, "isEnabled");
                    var k = admin.setKeyEnabled(keyId, enabled);
                    ObjectNode out = Json.object();
                    out.put("keyId", k.keyId());
                    out.put("tenantId", k.tenantId());
                    out.put("label", k.label());
                    out.put("isEnabled", k.enabled());
                    Json.send(ex, 200, out);
                });

        // ---- own-key rotation (any tenant) ----
        router.add(
                "PATCH",
                "/api/admin/tenant/key",
                (ex, pp, q) -> {
                    JsonNode body = Json.readObject(Json.readBody(ex));
                    JsonNode n = body.get("apiKey");
                    String secret = n != null && n.isTextual() ? n.asText() : null;
                    if (secret == null
                            || secret.length() < 32
                            || secret.length() > 512
                            || !printableNoWhitespace(secret)) {
                        throw new BadRequest("apiKey must be 32-512 printable characters with no whitespace");
                    }
                    long keyId = self.rotate(secret);
                    Json.send(ex, 200, Json.object().put("keyId", keyId));
                });
    }

    private static ObjectNode issuedTenant(IssuedTenant t) {
        ObjectNode out = Json.object();
        out.put("tenantId", t.tenantId());
        out.put("name", t.name());
        out.put("isOperator", t.operator());
        out.put("keyId", t.keyId());
        out.put("keyLabel", t.keyLabel());
        out.put("apiKey", t.apiKey());
        return out;
    }

    private static long pathId(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new NotFound();
        }
    }

    private static boolean printableNoWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7F || Character.isWhitespace(ch)) {
                return false;
            }
        }
        return true;
    }

}
