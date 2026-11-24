package fr.antschw.llmproxy.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;

/**
 * Helpers to write JSON responses, including errors in the OpenAI format
 * ({@code {"error": {"message", "type", "code"}}}) that every OpenAI-compatible client understands.
 */
final class Responses {

    static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private Responses() {
    }

    static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void error(HttpExchange exchange, int status, String type, String code, String message)
            throws IOException {
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.putObject("error")
                .put("message", message)
                .put("type", type)
                .put("code", code);
        json(exchange, status, error);
    }

    static void rateLimited(HttpExchange exchange, Duration retryAfter, String message) throws IOException {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        exchange.getResponseHeaders().set("Retry-After", Long.toString(seconds));
        error(exchange, 429, "rate_limit_error", "rate_limit_exceeded", message);
    }
}
