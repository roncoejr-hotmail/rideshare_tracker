package org.miosys.transport.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Jackson wiring plus request/response body helpers. */
public final class Json {

    public static final int MAX_BODY = 1024 * 1024;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** Reads the body, capped at 1 MiB. Anything larger is a 413. */
    public static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BODY) {
                    throw new ApiException.PayloadTooLarge();
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Parses a body that must be a JSON object. */
    public static JsonNode readObject(byte[] body) {
        if (body.length == 0) {
            throw new ApiException.BadRequest("request body must be a JSON object");
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (!node.isObject()) {
                throw new ApiException.BadRequest("request body must be a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new ApiException.BadRequest("request body must be a JSON object");
        }
    }

    public static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendEmpty(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
    }

    public static ObjectNode error(String message) {
        return MAPPER.createObjectNode().put("error", message);
    }
}
