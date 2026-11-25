package org.miosys.transport.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads and splits SQL scripts. Aware of comments and quoted strings. */
public final class SqlScripts {

    private SqlScripts() {}

    public static String readResource(String path) {
        try (InputStream in = SqlScripts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read resource: " + path, e);
        }
    }

    /** Splits a script on semicolons outside strings, quoted names, and comments. */
    public static List<String> split(String sql) {
        Objects.requireNonNull(sql);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'' || c == '"') {
                char q = c;
                cur.append(c);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    cur.append(d);
                    i++;
                    if (d == q) {
                        if (i < n && sql.charAt(i) == q) {
                            cur.append(q);
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (c == ';') {
                String stmt = cur.toString().strip();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        String tail = cur.toString().strip();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }
}
