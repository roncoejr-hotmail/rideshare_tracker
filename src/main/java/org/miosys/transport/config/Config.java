package org.miosys.transport.config;

/** Server configuration from environment variables. */
public record Config(String dbPath, String bind, int port, java.util.List<String> corsOrigins) {

    public static Config read() {
        String dbPath = orDefault(System.getenv("TRANSPORT_DB_PATH"), "./transport.db");
        String bind = orDefault(System.getenv("TRANSPORT_BIND"), "127.0.0.1");
        int port = 8080;
        String rawPort = System.getenv("TRANSPORT_PORT");
        if (rawPort != null && !rawPort.isBlank()) {
            try {
                port = Integer.parseInt(rawPort.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("TRANSPORT_PORT must be an integer port", e);
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("TRANSPORT_PORT must be 0-65535");
            }
        }
        return new Config(dbPath, bind, port, corsOrigins(System.getenv("TRANSPORT_CORS_ORIGINS")));
    }

    public static java.util.List<String> corsOrigins(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String origin = part.strip();
            if (!origin.isEmpty()) {
                out.add(origin);
            }
        }
        return java.util.List.copyOf(out);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
