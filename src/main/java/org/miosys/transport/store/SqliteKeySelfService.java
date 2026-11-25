package org.miosys.transport.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.miosys.transport.auth.RequestContext;
import org.miosys.transport.auth.Sha256;
import org.miosys.transport.db.Database;
import org.miosys.transport.db.Tx;
import org.miosys.transport.http.ApiException.Conflict;

/** Rotates the presenting key only. Other keys of the tenant are untouched. */
public final class SqliteKeySelfService implements KeySelfService {

    private static final String NOW = "strftime('%Y-%m-%dT%H:%M:%fZ','now')";

    private final Database db;

    public SqliteKeySelfService(Database db) {
        this.db = db;
    }

    @Override
    public long rotate(String newSecret) {
        var principal = RequestContext.current();
        String hash = Sha256.hex(newSecret);
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        try (PreparedStatement ps =
                                conn.prepareStatement(
                                        "SELECT 1 FROM tenant_key WHERE key_hash = ?")) {
                            ps.setString(1, hash);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    throw new Conflict("apiKey already in use");
                                }
                            }
                        }
                        try (PreparedStatement ps =
                                conn.prepareStatement(
                                        "UPDATE tenant_key SET key_hash = ?, updated_at = " + NOW
                                            + " WHERE key_id = ? AND tenant_id = ?")) {
                            ps.setString(1, hash);
                            ps.setLong(2, principal.keyId());
                            ps.setLong(3, principal.tenantId());
                            ps.executeUpdate();
                        }
                        return principal.keyId();
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }
}
