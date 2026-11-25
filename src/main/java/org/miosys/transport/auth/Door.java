package org.miosys.transport.auth;

import java.sql.SQLException;

/** Authenticates a request in MULTI-USER-AUTH order. Never reveals which step failed. */
public final class Door {

    private final KeyRepository keys;
    private final TenantRepository tenants;

    public Door(KeyRepository keys, TenantRepository tenants) {
        this.keys = keys;
        this.tenants = tenants;
    }

    public DoorResult open(String authorizationHeader) throws SQLException {
        var token = Bearer.parse(authorizationHeader);
        if (token.isEmpty()) {
            return DoorResult.Rejected.INSTANCE;
        }
        var key = keys.findByHash(Sha256.hex(token.get()));
        if (key.isEmpty() || !key.get().enabled()) {
            return DoorResult.Rejected.INSTANCE;
        }
        var tenant = tenants.find(key.get().tenantId());
        if (tenant.isEmpty() || !tenant.get().enabled() || tenant.get().id() != key.get().tenantId()) {
            return DoorResult.Rejected.INSTANCE;
        }
        var t = tenant.get();
        var k = key.get();
        return new DoorResult.Allowed(new Principal(t.id(), k.id(), t.operator()));
    }
}
