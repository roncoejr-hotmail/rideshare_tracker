package org.miosys.transport.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.miosys.transport.db.Database;

/** Tenant lookups for the door. One of the two places allowed cross-tenant SQL. */
public final class TenantRepository {

    public record TenantRow(long id, boolean operator, boolean enabled) {}

    private final Database db;

    public TenantRepository(Database db) {
        this.db = db;
    }

    public Optional<TenantRow> find(long tenantId) throws SQLException {
        try (Connection c = db.open();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT tenant_id, is_operator, is_enabled FROM tenant WHERE tenant_id = ?")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new TenantRow(rs.getLong(1), rs.getInt(2) == 1, rs.getInt(3) == 1));
            }
        }
    }
}
