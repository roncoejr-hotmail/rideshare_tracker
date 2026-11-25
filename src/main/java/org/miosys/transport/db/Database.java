package org.miosys.transport.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Opens SQLite connections. Every connection gets the required pragmas before first use. */
public final class Database {

    private final String jdbcUrl;
    private final String dbPath;

    public Database(String dbPath) {
        this.dbPath = dbPath;
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver missing", e);
        }
    }

    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    /** Switches the database file to WAL mode once. Persists in the file. */
    public void enableWal() throws SQLException {
        try (Connection c = open();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA journal_mode = WAL")) {
            rs.next();
        }
    }

    public String dbPath() {
        return dbPath;
    }
}
