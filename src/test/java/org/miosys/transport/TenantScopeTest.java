package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec section 13.5, code review item: every SQL statement in the transport store
 * filters by tenant first. Child-table inserts inherit ownership from the in-transaction
 * parent lookup, so they are the documented exception. Admin and door statements
 * are intentionally cross-tenant and live outside the scanned file.
 */
class TenantScopeTest {

    @Test
    void transportStoreStatementsAreTenantScoped() throws Exception {
        String source =
                Files.readString(
                        Paths.get("src/main/java/org/miosys/transport/store/SqliteTransportStore.java"),
                        StandardCharsets.UTF_8);
        List<String> statements = preparedStatements(source);
        assertTrue(statements.size() > 20, "expected many statements, found " + statements.size());
        for (String sql : statements) {
            String upper = sql.toUpperCase();
            boolean isQuery =
                    upper.contains("SELECT") || upper.contains("UPDATE") || upper.contains("DELETE");
            if (isQuery) {
                assertTrue(
                        sql.contains("tenant_id"),
                        "unscoped statement: " + sql);
            } else if (upper.contains("INSERT") && !upper.contains("RENTAL_ADJUSTMENT")) {
                assertTrue(
                        sql.contains("tenant_id"),
                        "unscoped insert: " + sql);
            }
        }
    }

    @Test
    void comparisonQueryIsTenantScoped() throws Exception {
        String sql;
        try (var in = getClass().getClassLoader().getResourceAsStream("sql/comparison.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The four top-level sources (settings, costs, rides, rentals) filter by tenant;
        // the rentals rollup and the adjustment subquery inherit scope through rental_net.
        long scoped = sql.split("tenant_id = \\?1", -1).length - 1;
        assertTrue(scoped == 4, "comparison.sql should filter every source by tenant, found " + scoped);
    }

    /** Extracts each prepareStatement("..."+...) literal span, string-aware. */
    private static List<String> preparedStatements(String source) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int call = source.indexOf("prepareStatement(", at);
            if (call < 0) {
                return out;
            }
            StringBuilder sql = new StringBuilder();
            int i = call + "prepareStatement(".length();
            int depth = 1;
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i);
                if (c == '"') {
                    i++;
                    while (i < source.length()) {
                        char d = source.charAt(i);
                        if (d == '\\' && i + 1 < source.length()) {
                            sql.append(source.charAt(i + 1));
                            i += 2;
                        } else if (d == '"') {
                            i++;
                            break;
                        } else {
                            sql.append(d);
                            i++;
                        }
                    }
                } else {
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                    }
                    i++;
                }
            }
            out.add(sql.toString());
            at = i;
        }
    }
}
