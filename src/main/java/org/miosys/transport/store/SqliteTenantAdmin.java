package org.miosys.transport.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.miosys.transport.auth.RequestContext;
import org.miosys.transport.auth.Secrets;
import org.miosys.transport.auth.Sha256;
import org.miosys.transport.db.Database;
import org.miosys.transport.db.Tx;
import org.miosys.transport.http.ApiException.Conflict;
import org.miosys.transport.http.ApiException.NotFound;
import org.miosys.transport.store.TenantAdmin.IssuedKey;
import org.miosys.transport.store.TenantAdmin.IssuedTenant;
import org.miosys.transport.store.TenantAdmin.KeyView;
import org.miosys.transport.store.TenantAdmin.TenantView;

/** Operator tenant/key administration. The only cross-tenant SQL besides the door. */
public final class SqliteTenantAdmin implements TenantAdmin {

    private static final String NOW = "strftime('%Y-%m-%dT%H:%M:%fZ','now')";

    private final Database db;

    public SqliteTenantAdmin(Database db) {
        this.db = db;
    }

    @Override
    public IssuedTenant bootstrap(String name, String keyLabel) {
        // No principal here: this runs on the open route.
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        try (PreparedStatement ps =
                                        conn.prepareStatement("SELECT COUNT(*) FROM tenant");
                                ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            if (rs.getLong(1) > 0) {
                                throw new NotFound();
                            }
                        }
                        long tenantId = insertTenant(conn, name, true);
                        String secret = Secrets.generate();
                        long keyId = insertKey(conn, tenantId, Sha256.hex(secret), keyLabel);
                        return new IssuedTenant(tenantId, name, true, keyId, keyLabel, secret);
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public IssuedTenant createTenant(String name, String keyLabel) {
        RequestContext.current();
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        long tenantId = insertTenant(conn, name, false);
                        String secret = Secrets.generate();
                        long keyId = insertKey(conn, tenantId, Sha256.hex(secret), keyLabel);
                        return new IssuedTenant(tenantId, name, false, keyId, keyLabel, secret);
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public List<TenantView> tenants() {
        RequestContext.current();
        try (Connection c = db.open();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT tenant_id, name, is_operator, is_enabled, created_at, updated_at"
                                    + " FROM tenant ORDER BY tenant_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<TenantView> out = new ArrayList<>();
                while (rs.next()) {
                    long id = rs.getLong(1);
                    out.add(
                            new TenantView(
                                    id,
                                    rs.getString(2),
                                    rs.getInt(3) == 1,
                                    rs.getInt(4) == 1,
                                    rs.getString(5),
                                    rs.getString(6),
                                    keysOf(c, id)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public TenantView updateTenant(long tenantId, Optional<String> name, Optional<Boolean> enabled) {
        var principal = RequestContext.current();
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        TenantView current = readTenant(conn, tenantId).orElseThrow(NotFound::new);
                        if (enabled.isPresent()
                                && !enabled.get()
                                && tenantId == principal.tenantId()) {
                            throw new Conflict("cannot disable your own tenant");
                        }
                        String newName = name.orElse(current.name());
                        boolean newEnabled = enabled.orElse(current.enabled());
                        try (PreparedStatement ps =
                                conn.prepareStatement(
                                        "UPDATE tenant SET name = ?, is_enabled = ?, updated_at = " + NOW
                                            + " WHERE tenant_id = ?")) {
                            ps.setString(1, newName);
                            ps.setInt(2, newEnabled ? 1 : 0);
                            ps.setLong(3, tenantId);
                            ps.executeUpdate();
                        }
                        return readTenant(conn, tenantId).orElseThrow();
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public IssuedKey issueKey(long tenantId, String label) {
        RequestContext.current();
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        if (readTenant(conn, tenantId).isEmpty()) {
                            throw new NotFound();
                        }
                        String secret = Secrets.generate();
                        long keyId = insertKey(conn, tenantId, Sha256.hex(secret), label);
                        return new IssuedKey(keyId, tenantId, label, secret);
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public KeyView setKeyEnabled(long keyId, boolean enabled) {
        var principal = RequestContext.current();
        try (Connection c = db.open()) {
            return Tx.write(
                    c,
                    conn -> {
                        KeyView target = readKey(conn, keyId).orElseThrow(NotFound::new);
                        if (!enabled && target.enabled()) {
                            if (keyId == principal.keyId()) {
                                throw new Conflict("cannot disable the key in use");
                            }
                            if (isOperator(conn, target.tenantId())
                                    && enabledKeyCount(conn, target.tenantId()) == 1) {
                                throw new Conflict("cannot disable the operator's last key");
                            }
                        }
                        try (PreparedStatement ps =
                                conn.prepareStatement(
                                        "UPDATE tenant_key SET is_enabled = ?, updated_at = " + NOW
                                            + " WHERE key_id = ?")) {
                            ps.setInt(1, enabled ? 1 : 0);
                            ps.setLong(2, keyId);
                            ps.executeUpdate();
                        }
                        return readKey(conn, keyId).orElseThrow();
                    });
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private static long insertTenant(Connection c, String name, boolean operator) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO tenant (name, is_operator) VALUES (?, ?)",
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setInt(2, operator ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertKey(Connection c, long tenantId, String hash, String label)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO tenant_key (tenant_id, key_hash, label) VALUES (?, ?, ?)",
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, tenantId);
            ps.setString(2, hash);
            ps.setString(3, label);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static Optional<TenantView> readTenant(Connection c, long tenantId) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT tenant_id, name, is_operator, is_enabled, created_at, updated_at"
                            + " FROM tenant WHERE tenant_id = ?")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new TenantView(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getInt(3) == 1,
                                rs.getInt(4) == 1,
                                rs.getString(5),
                                rs.getString(6),
                                keysOf(c, tenantId)));
            }
        }
    }

    private static List<KeyView> keysOf(Connection c, long tenantId) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT key_id, tenant_id, label, is_enabled, created_at, updated_at"
                            + " FROM tenant_key WHERE tenant_id = ? ORDER BY key_id")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                List<KeyView> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(
                            new KeyView(
                                    rs.getLong(1),
                                    rs.getLong(2),
                                    rs.getString(3),
                                    rs.getInt(4) == 1,
                                    rs.getString(5),
                                    rs.getString(6)));
                }
                return out;
            }
        }
    }

    private static Optional<KeyView> readKey(Connection c, long keyId) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT key_id, tenant_id, label, is_enabled, created_at, updated_at"
                            + " FROM tenant_key WHERE key_id = ?")) {
            ps.setLong(1, keyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new KeyView(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getInt(4) == 1,
                                rs.getString(5),
                                rs.getString(6)));
            }
        }
    }

    private static boolean isOperator(Connection c, long tenantId) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT is_operator FROM tenant WHERE tenant_id = ?")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private static long enabledKeyCount(Connection c, long tenantId) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COUNT(*) FROM tenant_key WHERE tenant_id = ? AND is_enabled = 1")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
