package fr.antschw.llmproxy.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** A programmable OpenAI-compatible server running in the test JVM. */
final class FakeUpstream implements AutoCloseable {

    /** A request as received by the fake upstream. */
    record Received(String method, String path, String query, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst().orElse(null);
        }
    }

    /** Produces the response to a request. */
    @FunctionalInterface
    interface Behavior {
        void respond(HttpExchange exchange, Received request) throws IOException;
    }

    private final String name;
    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Behavior behavior;

    FakeUpstream(String name) throws IOException {
        this.name = name;
        this.behavior = ok();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try (exchange) {
                Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                received.add(request);
                behavior.respond(exchange, request);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    List<Received> received() {
        return received;
    }

    FakeUpstream behave(Behavior behavior) {
        this.behavior = behavior;
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Answers 200 with a chat completion naming this upstream and the model it received. */
    Behavior ok() {
        return (exchange, request) -> {
            String model = request.body().replaceAll("(?s).*\"model\"\\s*:\\s*\"([^\"]*)\".*", "$1");
            send(exchange, 200, "{\"id\":\"cmpl\",\"object\":\"chat.completion\",\"upstream\":\"" + name
                    + "\",\"model\":\"" + model + "\",\"choices\":[]}", Map.of("x-request-id", "req-" + name));
        };
    }

    static Behavior status(int status, Map<String, String> headers) {
        return (exchange, request) -> send(exchange, status,
                "{\"error\":{\"message\":\"status " + status + "\",\"type\":\"test\"}}", headers);
    }

    static void send(HttpExchange exchange, int status, String body, Map<String, String> headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        headers.forEach(exchange.getResponseHeaders()::set);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
