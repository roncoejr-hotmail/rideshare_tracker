package org.miosys.transport.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.miosys.transport.db.SqlScripts;
import org.miosys.transport.domain.MonthlyComparison;

/** Runs the monthly comparison query for one tenant. Amounts come back already rounded. */
public final class ComparisonQuery {

    private static volatile String sql;

    private ComparisonQuery() {}

    public static List<MonthlyComparison> run(Connection c, long tenantId, LocalDate asOf)
            throws SQLException {
        String query = sql;
        if (query == null) {
            query = SqlScripts.readResource("sql/comparison.sql");
            sql = query;
        }
        try (PreparedStatement ps = c.prepareStatement(query)) {
            ps.setLong(1, tenantId);
            ps.setString(2, asOf.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<MonthlyComparison> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(
                            new MonthlyComparison(
                                    YearMonth.parse(rs.getString("month")),
                                    LocalDate.parse(rs.getString("window_from")),
                                    LocalDate.parse(rs.getString("window_to")),
                                    rs.getInt("days"),
                                    rs.getLong("old_cents"),
                                    rs.getLong("ride_cents"),
                                    rs.getInt("ride_count"),
                                    rs.getLong("rental_cents"),
                                    rs.getInt("rental_count"),
                                    rs.getLong("recurring_new_cents"),
                                    rs.getInt("excluded_ride_count"),
                                    rs.getInt("excluded_rental_count")));
                }
                return out;
            }
        }
    }
}
