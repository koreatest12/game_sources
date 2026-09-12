package io.github.koreatest12.game;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

public final class GameServer {
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8080;
    private static final Instant STARTED_AT = Instant.now();
    private static final LongAdder REQUESTS = new LongAdder();
    private static final String ADMIN_TOKEN = envOrDefault("ADMIN_TOKEN", "");

    private GameServer() {
    }

    public static void main(String[] args) throws IOException {
        String host = envOrDefault("HOST", DEFAULT_HOST);
        int port = parsePort(envOrDefault("PORT", Integer.toString(DEFAULT_PORT)));
        FileTransferService fileTransferService = FileTransferService.fromEnvironment();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 128);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.createContext("/health", new HealthHandler());
        server.createContext("/ready", new HealthHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/metrics", new MetricsHandler());
        fileTransferService.register(server);
        server.createContext("/", new StaticResourceHandler());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(5);
            executor.shutdown();
        }, "game-server-shutdown"));

        server.start();
        System.out.printf("game_sources server started on http://%s:%d%n", host, port);
        System.out.printf("Astra Fly DOOM: http://%s:%d/astra-fly-doom/%n", host, port);
        System.out.printf("file transfer storage: %s (max upload: %d bytes, public downloads: %s)%n",
                fileTransferService.storageDirectory(), fileTransferService.maxUploadBytes(), fileTransferService.publicDownloads());
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("PORT must be between 1 and 65535");
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("PORT must be a number", ex);
        }
    }

    private static boolean requireGet(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}", false);
            return false;
        }
        return true;
    }

    private static boolean authorized(HttpExchange exchange) {
        if (ADMIN_TOKEN.isBlank()) return true;
        String actual = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + ADMIN_TOKEN;
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body, boolean cache) throws IOException {
        sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8), cache);
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes, boolean cache) throws IOException {
        REQUESTS.increment();
        securityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", cache ? "public, max-age=3600" : "no-store");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static final class HealthHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) return;
            send(exchange, 200, "application/json; charset=utf-8", "{\"status\":\"UP\"}", false);
        }
    }

    private static final class StatusHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) return;
            long uptime = Duration.between(STARTED_AT, Instant.now()).toSeconds();
            String body = "{\"service\":\"game_sources\",\"status\":\"running\",\"startedAt\":\"" + jsonEscape(STARTED_AT.toString())
                    + "\",\"uptimeSeconds\":" + uptime
                    + ",\"java\":\"" + jsonEscape(System.getProperty("java.version"))
                    + "\",\"fileTransfer\":true,\"astraFlyDoom\":true,\"astraFlyDoomPath\":\"/astra-fly-doom/\"}";
            send(exchange, 200, "application/json; charset=utf-8", body, false);
        }
    }

    private static final class MetricsHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) return;
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, "application/json; charset=utf-8", "{\"error\":\"unauthorized\"}", false);
                return;
            }
            long uptime = Duration.between(STARTED_AT, Instant.now()).toSeconds();
            String body = "{\"requests\":" + REQUESTS.sum() + ",\"uptimeSeconds\":" + uptime + ",\"processors\":" + Runtime.getRuntime().availableProcessors() + "}";
            send(exchange, 200, "application/json; charset=utf-8", body, false);
        }
    }

    private static final class StaticResourceHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) return;
            String requestPath = exchange.getRequestURI().getPath();
            String resourcePath;
            if ("/".equals(requestPath)) {
                resourcePath = "/index.html";
            } else if (requestPath.endsWith("/")) {
                resourcePath = requestPath + "index.html";
            } else {
                resourcePath = requestPath;
            }

            if (resourcePath.contains("..") || resourcePath.contains("\\") || resourcePath.indexOf('\0') >= 0) {
                send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"bad_path\"}", false);
                return;
            }
            try (InputStream stream = GameServer.class.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    send(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"not_found\"}", false);
                    return;
                }
                byte[] bytes = stream.readAllBytes();
                boolean cache = resourcePath.startsWith("/assets/")
                        || resourcePath.startsWith("/static/")
                        || (resourcePath.startsWith("/astra-fly-doom/") && !resourcePath.endsWith(".html"));
                sendBytes(exchange, 200, contentType(resourcePath), bytes, cache);
            }
        }
    }
}
