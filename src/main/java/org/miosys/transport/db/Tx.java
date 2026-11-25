package org.miosys.transport.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Write transactions use BEGIN IMMEDIATE so concurrent writers wait instead of failing. */
public final class Tx {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private Tx() {}

    public static <T> T write(Connection c, Work<T> work) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            try {
                T value = work.run(c);
                st.execute("COMMIT");
                return value;
            } catch (RuntimeException | SQLException e) {
                try {
                    st.execute("ROLLBACK");
                } catch (SQLException ignored) {
                    // original failure is what matters
                }
                throw e;
            }
        }
    }
}
