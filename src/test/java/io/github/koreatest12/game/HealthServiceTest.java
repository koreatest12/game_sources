package io.github.koreatest12.game;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthServiceTest {
    @TempDir
    Path storage;

    private HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private HealthService service(long minFreeBytes, boolean authorized) {
        return new HealthService(storage, minFreeBytes, Instant.now(), exchange -> authorized);
    }

    private String base(HealthService health) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        health.register(server);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void livenessIsUpEvenBeforeStartupCompletes() throws Exception {
        HealthService health = service(0, true);
        String base = base(health);

        for (String path : new String[] {"/health", "/health/live"}) {
            HttpResponse<String> response = get(base + path);
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.body().contains("\"status\":\"UP\""), path);
            assertTrue(response.body().contains("\"probe\":\"liveness\""), path);
        }
    }

    @Test
    void startupAndReadinessWaitForMarkStarted() throws Exception {
        HealthService health = service(0, true);
        String base = base(health);

        assertEquals(503, get(base + "/health/startup").statusCode());
        assertEquals(503, get(base + "/ready").statusCode());
        assertEquals(503, get(base + "/health/ready").statusCode());

        health.markStarted();

        assertEquals(200, get(base + "/health/startup").statusCode());
        HttpResponse<String> ready = get(base + "/ready");
        assertEquals(200, ready.statusCode());
        assertTrue(ready.body().contains("\"storage\":{\"status\":\"UP\""));
        assertTrue(ready.body().contains("\"disk\":{\"status\":\"UP\""));
        assertFalse(ready.body().contains("usableBytes"), "public readiness must not expose disk details");
        assertEquals(200, get(base + "/health/ready").statusCode());
    }

    @Test
    void readinessFailsWhileShuttingDownButLivenessStaysUp() throws Exception {
        HealthService health = service(0, true);
        health.markStarted();
        String base = base(health);

        health.markShuttingDown();

        HttpResponse<String> ready = get(base + "/ready");
        assertEquals(503, ready.statusCode());
        assertTrue(ready.body().contains("\"shutdown\":{\"status\":\"DOWN\""));
        assertEquals(200, get(base + "/health/live").statusCode());
    }

    @Test
    void readinessFailsWhenDiskIsBelowThreshold() throws Exception {
        HealthService health = service(Long.MAX_VALUE, true);
        health.markStarted();
        String base = base(health);

        HttpResponse<String> ready = get(base + "/health/ready");
        assertEquals(503, ready.statusCode());
        assertTrue(ready.body().contains("\"disk\":{\"status\":\"DOWN\""));
        assertEquals(200, get(base + "/health").statusCode());
    }

    @Test
    void storageCheckFailsWhenDirectoryIsMissing() {
        HealthService health = new HealthService(storage.resolve("missing"), 0, Instant.now(), exchange -> true);
        health.markStarted();

        assertFalse(health.storageCheck().up());
        assertFalse(HealthService.allUp(health.readinessChecks()));
    }

    @Test
    void detailsRequiresAuthorization() throws Exception {
        HealthService health = service(0, false);
        health.markStarted();
        String base = base(health);

        HttpResponse<String> response = get(base + "/health/details");
        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(""));
    }

    @Test
    void detailsIncludesChecksAndJvmWhenAuthorized() throws Exception {
        HealthService health = service(0, true);
        health.markStarted();
        String base = base(health);

        HttpResponse<String> response = get(base + "/health/details");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"probe\":\"details\""));
        assertTrue(body.contains("\"checks\":{"));
        assertTrue(body.contains("\"detail\":\"usableBytes="));
        assertTrue(body.contains("\"heapUsedBytes\":"));
        assertTrue(body.contains("\"uptimeSeconds\":"));
        assertTrue(body.endsWith("}"));
    }

    @Test
    void unknownHealthSubPathsReturnNotFound() throws Exception {
        HealthService health = service(0, true);
        health.markStarted();
        String base = base(health);

        assertEquals(404, get(base + "/health/unknown").statusCode());
        assertEquals(404, get(base + "/health/live/extra").statusCode());
        assertEquals(404, get(base + "/ready/extra").statusCode());
    }

    @Test
    void rejectsNonGetMethods() throws Exception {
        HealthService health = service(0, true);
        String base = base(health);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/health")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }
}
