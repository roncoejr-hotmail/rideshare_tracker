package org.miosys.transport.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.miosys.transport.db.Database;

/** Key lookups for the door. One of the two places allowed cross-tenant SQL. */
public final class KeyRepository {

    public record KeyRow(long id, long tenantId, boolean enabled) {}

    private final Database db;

    public KeyRepository(Database db) {
        this.db = db;
    }

    public Optional<KeyRow> findByHash(String hash) throws SQLException {
        try (Connection c = db.open();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT key_id, tenant_id, is_enabled FROM tenant_key WHERE key_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new KeyRow(rs.getLong(1), rs.getLong(2), rs.getInt(3) == 1));
            }
        }
    }
}
