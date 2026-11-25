package org.miosys.transport.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Forward-only migrations tracked by PRAGMA user_version. Runs before the port opens. */
public final class Migrations {

    public record Migration(int version, String resource) {}

    static final List<Migration> KNOWN = List.of(new Migration(1, "db/migration/V1__schema.sql"));

    private Migrations() {}

    /** Applies pending migrations and verifies integrity. Returns the schema version. */
    public static int run(Database db) throws SQLException {
        db.enableWal();
        try (Connection c = db.open()) {
            int current = userVersion(c);
            int max = KNOWN.stream().mapToInt(Migration::version).max().orElseThrow();
            if (current > max) {
                throw new IllegalStateException(
                        "database schema version " + current + " is newer than supported " + max);
            }
            for (Migration m : KNOWN) {
                if (m.version() > current) {
                    apply(c, m);
                }
            }
            foreignKeyCheck(c);
            integrityCheck(c);
            return userVersion(c);
        }
    }

    static void apply(Connection c, Migration m) throws SQLException {
        String sql = SqlScripts.readResource(m.resource());
        try (Statement st = c.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            try {
                for (String stmt : SqlScripts.split(sql)) {
                    st.execute(stmt);
                }
                st.execute("PRAGMA user_version = " + m.version());
                st.execute("COMMIT");
            } catch (SQLException e) {
                rollbackQuietly(st);
                throw new SQLException("migration " + m.version() + " failed, rolled back", e);
            }
        }
    }

    static int userVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void foreignKeyCheck(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) {
                throw new IllegalStateException("PRAGMA foreign_key_check found violations at startup");
            }
        }
    }

    private static void integrityCheck(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new IllegalStateException("PRAGMA integrity_check failed at startup");
            }
        }
    }

    private static void rollbackQuietly(Statement st) {
        try {
            st.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // original failure is what matters
        }
    }
}
