package io.github.koreatest12.game;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 헬스체크를 목적별 프로브로 분리한다.
 *
 * <ul>
 *   <li>liveness  (/health, /health/live)     : 프로세스가 요청에 응답하는가. 외부 의존성은 보지 않는다.</li>
 *   <li>startup   (/health/startup)           : 서버 초기화(리스너 기동)가 끝났는가.</li>
 *   <li>readiness (/ready, /health/ready)     : 지금 트래픽을 받아도 되는가. 기동 완료·종료 중 아님·저장소 쓰기 가능·디스크 여유.</li>
 *   <li>details   (/health/details)           : 전체 점검 항목과 JVM 상태. ADMIN_TOKEN 인증 필요.</li>
 * </ul>
 *
 * liveness에 저장소·디스크 같은 의존성을 넣지 않는 이유: 디스크가 차서 liveness가 실패하면
 * 오케스트레이터가 컨테이너를 반복 재시작하지만 재시작으로는 디스크 문제가 해결되지 않기 때문이다.
 * 이런 상황은 readiness 실패(트래픽 차단)로 처리하는 것이 맞다.
 */
final class HealthService {
    static final long DEFAULT_MIN_FREE_BYTES = 64L * 1024 * 1024;

    private static final String JSON = "application/json; charset=utf-8";

    private final Path storageDirectory;
    private final long minFreeBytes;
    private final Instant startedAt;
    private final Predicate<HttpExchange> authorizer;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    HealthService(Path storageDirectory, long minFreeBytes, Instant startedAt, Predicate<HttpExchange> authorizer) {
        if (minFreeBytes < 0) throw new IllegalArgumentException("HEALTH_MIN_FREE_BYTES must be >= 0");
        this.storageDirectory = storageDirectory;
        this.minFreeBytes = minFreeBytes;
        this.startedAt = startedAt;
        this.authorizer = authorizer;
    }

    static HealthService fromEnvironment(Path storageDirectory, Instant startedAt, Predicate<HttpExchange> authorizer) {
        String raw = System.getenv("HEALTH_MIN_FREE_BYTES");
        long minFree = DEFAULT_MIN_FREE_BYTES;
        if (raw != null && !raw.isBlank()) {
            try {
                minFree = Long.parseLong(raw.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("HEALTH_MIN_FREE_BYTES must be a number", ex);
            }
        }
        return new HealthService(storageDirectory, minFree, startedAt, authorizer);
    }

    void markStarted() {
        started.set(true);
    }

    void markShuttingDown() {
        shuttingDown.set(true);
    }

    long minFreeBytes() {
        return minFreeBytes;
    }

    record Check(String name, boolean up, String detail) {
        String toJson(boolean includeDetail) {
            String head = "\"" + name + "\":{\"status\":\"" + (up ? "UP" : "DOWN") + "\"";
            return includeDetail ? head + ",\"detail\":\"" + GameServer.jsonEscape(detail) + "\"}" : head + "}";
        }
    }

    Check startupCheck() {
        return started.get()
                ? new Check("startup", true, "listener started")
                : new Check("startup", false, "server is still starting");
    }

    Check shutdownCheck() {
        return shuttingDown.get()
                ? new Check("shutdown", false, "server is shutting down")
                : new Check("shutdown", true, "accepting traffic");
    }

    Check storageCheck() {
        if (!Files.isDirectory(storageDirectory)) {
            return new Check("storage", false, "storage directory is missing");
        }
        if (!Files.isWritable(storageDirectory)) {
            return new Check("storage", false, "storage directory is not writable");
        }
        return new Check("storage", true, "storage directory is writable");
    }

    Check diskCheck() {
        try {
            long usable = Files.getFileStore(storageDirectory).getUsableSpace();
            boolean up = usable >= minFreeBytes;
            return new Check("disk", up, "usableBytes=" + usable + ", minFreeBytes=" + minFreeBytes);
        } catch (IOException | RuntimeException ex) {
            return new Check("disk", false, "cannot read file store: " + ex.getClass().getSimpleName());
        }
    }

    List<Check> readinessChecks() {
        List<Check> checks = new ArrayList<>();
        checks.add(startupCheck());
        checks.add(shutdownCheck());
        checks.add(storageCheck());
        checks.add(diskCheck());
        return checks;
    }

    static boolean allUp(List<Check> checks) {
        return checks.stream().allMatch(Check::up);
    }

    String livenessBody() {
        return "{\"status\":\"UP\",\"probe\":\"liveness\"}";
    }

    String probeBody(String probe, List<Check> checks) {
        return probeBody(probe, checks, false);
    }

    String probeBody(String probe, List<Check> checks, boolean includeDetail) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(allUp(checks) ? "UP" : "DOWN").append("\",\"probe\":\"").append(probe).append("\",\"checks\":{");
        for (int i = 0; i < checks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(checks.get(i).toJson(includeDetail));
        }
        return sb.append("}}").toString();
    }

    String detailsBody(List<Check> checks) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long uptime = Duration.between(startedAt, Instant.now()).toSeconds();
        String base = probeBody("details", checks, true);
        String jvm = ",\"jvm\":{\"javaVersion\":\"" + GameServer.jsonEscape(System.getProperty("java.version"))
                + "\",\"heapUsedBytes\":" + heap.getUsed()
                + ",\"heapMaxBytes\":" + heap.getMax()
                + ",\"availableProcessors\":" + Runtime.getRuntime().availableProcessors()
                + ",\"liveThreads\":" + ManagementFactory.getThreadMXBean().getThreadCount()
                + "},\"startedAt\":\"" + GameServer.jsonEscape(startedAt.toString())
                + "\",\"uptimeSeconds\":" + uptime + "}";
        return base.substring(0, base.length() - 1) + jvm;
    }

    void register(HttpServer server) {
        HttpHandler liveness = exactly(exchange -> GameServer.send(exchange, 200, JSON, livenessBody(), false));
        HttpHandler readiness = exactly(exchange -> {
            List<Check> checks = readinessChecks();
            GameServer.send(exchange, allUp(checks) ? 200 : 503, JSON, probeBody("readiness", checks), false);
        });

        server.createContext("/health", liveness);
        server.createContext("/health/live", liveness);
        server.createContext("/ready", readiness);
        server.createContext("/health/ready", readiness);
        server.createContext("/health/startup", exactly(exchange -> {
            List<Check> checks = List.of(startupCheck());
            GameServer.send(exchange, allUp(checks) ? 200 : 503, JSON, probeBody("startup", checks), false);
        }));
        server.createContext("/health/details", exactly(exchange -> {
            if (!authorizer.test(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                GameServer.send(exchange, 401, JSON, "{\"error\":\"unauthorized\"}", false);
                return;
            }
            List<Check> checks = readinessChecks();
            GameServer.send(exchange, allUp(checks) ? 200 : 503, JSON, detailsBody(checks), false);
        }));
    }

    private static HttpHandler exactly(HttpHandler delegate) {
        return exchange -> {
            if (!GameServer.requireGet(exchange)) return;
            String path = exchange.getRequestURI().getPath();
            String context = exchange.getHttpContext().getPath();
            if (!path.equals(context)) {
                GameServer.send(exchange, 404, JSON, "{\"error\":\"not_found\"}", false);
                return;
            }
            delegate.handle(exchange);
        };
    }
}
