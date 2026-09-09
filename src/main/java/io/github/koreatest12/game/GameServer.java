package io.github.koreatest12.game;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameServer {
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8080;

    private GameServer() {
    }

    public static void main(String[] args) throws IOException {
        String host = envOrDefault("HOST", DEFAULT_HOST);
        int port = parsePort(envOrDefault("PORT", Integer.toString(DEFAULT_PORT)));

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.createContext("/health", new HealthHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/", new RootHandler());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            executor.shutdown();
        }, "game-server-shutdown"));

        server.start();
        System.out.printf("game_sources server started on http://%s:%d%n", host, port);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("PORT must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("PORT must be a number", ex);
        }
    }

    private static boolean requireGet(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
            return false;
        }
        return true;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) {
                return;
            }
            send(exchange, 200, "application/json; charset=utf-8", "{\"status\":\"UP\"}");
        }
    }

    private static final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) {
                return;
            }
            String body = "{\"service\":\"game_sources\",\"status\":\"running\",\"time\":\""
                    + Instant.now() + "\"}";
            send(exchange, 200, "application/json; charset=utf-8", body);
        }
    }

    private static final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) {
                return;
            }
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"not_found\"}");
                return;
            }

            try (InputStream stream = GameServer.class.getResourceAsStream("/index.html")) {
                if (stream == null) {
                    send(exchange, 500, "application/json; charset=utf-8", "{\"error\":\"index_missing\"}");
                    return;
                }
                String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                send(exchange, 200, "text/html; charset=utf-8", html);
            }
        }
    }
}
