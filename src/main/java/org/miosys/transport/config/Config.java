package org.miosys.transport.config;

/** Server configuration from environment variables. */
public record Config(String dbPath, String bind, int port) {

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
        return new Config(dbPath, bind, port);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
