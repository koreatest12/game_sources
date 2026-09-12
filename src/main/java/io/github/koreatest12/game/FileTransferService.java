package io.github.koreatest12.game;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class FileTransferService {
    private static final String API_PREFIX = "/api/files";
    private static final String DOWNLOAD_PREFIX = "/files";
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final Path storageDirectory;
    private final long maxUploadBytes;
    private final boolean publicDownloads;
    private final String adminToken;

    private FileTransferService(Path storageDirectory, long maxUploadBytes, boolean publicDownloads, String adminToken)
            throws IOException {
        this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
        this.maxUploadBytes = maxUploadBytes;
        this.publicDownloads = publicDownloads;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        Files.createDirectories(this.storageDirectory);
        if (!Files.isDirectory(this.storageDirectory) || !Files.isWritable(this.storageDirectory)) {
            throw new IOException("FILE_STORAGE_DIR must be a writable directory: " + this.storageDirectory);
        }
    }

    static FileTransferService fromEnvironment() throws IOException {
        Path storage = Path.of(envOrDefault("FILE_STORAGE_DIR", "data/files"));
        long maxBytes = parsePositiveLong(envOrDefault("MAX_UPLOAD_BYTES", Long.toString(DEFAULT_MAX_UPLOAD_BYTES)), "MAX_UPLOAD_BYTES");
        boolean publicDownloads = Boolean.parseBoolean(envOrDefault("FILE_PUBLIC_DOWNLOADS", "false"));
        return new FileTransferService(storage, maxBytes, publicDownloads, envOrDefault("ADMIN_TOKEN", ""));
    }

    void register(HttpServer server) {
        server.createContext(API_PREFIX, new ApiHandler());
        server.createContext(DOWNLOAD_PREFIX, new DownloadHandler());
    }

    Path storageDirectory() {
        return storageDirectory;
    }

    long maxUploadBytes() {
        return maxUploadBytes;
    }

    boolean publicDownloads() {
        return publicDownloads;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static long parsePositiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new IllegalArgumentException(name + " must be greater than zero");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a positive integer", ex);
        }
    }

    private final class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requireAdmin(exchange)) return;

            String rawPath = exchange.getRequestURI().getRawPath();
            String remainder = rawPath.length() <= API_PREFIX.length() ? "" : rawPath.substring(API_PREFIX.length());
            if (remainder.isEmpty() || "/".equals(remainder)) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                listFiles(exchange);
                return;
            }

            if (!remainder.startsWith("/") || remainder.length() == 1) {
                sendJson(exchange, 404, "{\"error\":\"not_found\"}");
                return;
            }

            String fileName;
            try {
                fileName = decodeAndValidateFileName(remainder.substring(1));
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, "{\"error\":\"invalid_file_name\",\"message\":\"" + jsonEscape(ex.getMessage()) + "\"}");
                return;
            }

            switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
                case "GET" -> describeFile(exchange, fileName);
                case "PUT", "POST" -> storeFile(exchange, fileName);
                case "DELETE" -> deleteFile(exchange, fileName);
                default -> methodNotAllowed(exchange, "GET, PUT, POST, DELETE");
            }
        }
    }

    private final class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                methodNotAllowed(exchange, "GET, HEAD");
                return;
            }
            if (!publicDownloads && !requireAdmin(exchange)) return;

            String rawPath = exchange.getRequestURI().getRawPath();
            String remainder = rawPath.length() <= DOWNLOAD_PREFIX.length() ? "" : rawPath.substring(DOWNLOAD_PREFIX.length());
            if (!remainder.startsWith("/") || remainder.length() == 1) {
                sendJson(exchange, 404, "{\"error\":\"file_name_required\"}");
                return;
            }

            String fileName;
            try {
                fileName = decodeAndValidateFileName(remainder.substring(1));
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, "{\"error\":\"invalid_file_name\"}");
                return;
            }
            streamFile(exchange, fileName, "HEAD".equals(method));
        }
    }

    private boolean requireAdmin(HttpExchange exchange) throws IOException {
        if (adminToken.isBlank()) {
            sendJson(exchange, 503, "{\"error\":\"admin_token_not_configured\"}");
            return false;
        }
        String actual = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + adminToken;
        boolean valid = actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
            return false;
        }
        return true;
    }

    private void listFiles(HttpExchange exchange) throws IOException {
        List<FileMetadata> files = new ArrayList<>();
        try (var stream = Files.list(storageDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(".upload-"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> {
                        try {
                            files.add(metadata(path));
                        } catch (IOException ex) {
                            throw new MetadataReadException(ex);
                        }
                    });
        } catch (MetadataReadException ex) {
            throw ex.ioException;
        }

        StringBuilder body = new StringBuilder(256 + files.size() * 160);
        body.append("{\"maxUploadBytes\":").append(maxUploadBytes)
                .append(",\"publicDownloads\":").append(publicDownloads)
                .append(",\"files\":[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) body.append(',');
            body.append(files.get(i).toJson());
        }
        body.append("]}");
        sendJson(exchange, 200, body.toString());
    }

    private void describeFile(HttpExchange exchange, String fileName) throws IOException {
        Path target = resolveFile(fileName);
        if (!Files.isRegularFile(target)) {
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        sendJson(exchange, 200, metadata(target).toJson());
    }

    private void storeFile(HttpExchange exchange, String fileName) throws IOException {
        String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthHeader != null) {
            try {
                long contentLength = Long.parseLong(contentLengthHeader);
                if (contentLength > maxUploadBytes) {
                    sendJson(exchange, 413, "{\"error\":\"file_too_large\",\"maxUploadBytes\":" + maxUploadBytes + "}");
                    return;
                }
            } catch (NumberFormatException ignored) {
                // The streaming limit below is authoritative.
            }
        }

        Path target = resolveFile(fileName);
        boolean existed = Files.exists(target);
        Path temp = Files.createTempFile(storageDirectory, ".upload-", ".part");
        long size = 0;
        MessageDigest digest = sha256Digest();
        try {
            try (InputStream input = exchange.getRequestBody();
                 OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > maxUploadBytes) {
                        throw new UploadTooLargeException();
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            moveAtomically(temp, target);
        } catch (UploadTooLargeException ex) {
            Files.deleteIfExists(temp);
            sendJson(exchange, 413, "{\"error\":\"file_too_large\",\"maxUploadBytes\":" + maxUploadBytes + "}");
            return;
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }

        String sha256 = HexFormat.of().formatHex(digest.digest());
        FileMetadata metadata = new FileMetadata(fileName, size, sha256, Files.getLastModifiedTime(target).toInstant());
        sendJson(exchange, existed ? 200 : 201, metadata.toJson());
    }

    private void deleteFile(HttpExchange exchange, String fileName) throws IOException {
        Path target = resolveFile(fileName);
        if (!Files.deleteIfExists(target)) {
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        sendJson(exchange, 200, "{\"deleted\":true,\"name\":\"" + jsonEscape(fileName) + "\"}");
    }

    private void streamFile(HttpExchange exchange, String fileName, boolean headOnly) throws IOException {
        Path target = resolveFile(fileName);
        if (!Files.isRegularFile(target)) {
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }

        long fileSize = Files.size(target);
        ByteRange range;
        try {
            range = parseRange(exchange.getRequestHeaders().getFirst("Range"), fileSize);
        } catch (IllegalArgumentException ex) {
            applySecurityHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + fileSize);
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
        }

        long start = range == null ? 0 : range.start;
        long end = range == null ? Math.max(0, fileSize - 1) : range.end;
        long length = fileSize == 0 ? 0 : end - start + 1;
        int status = range == null ? 200 : 206;

        Headers headers = exchange.getResponseHeaders();
        applySecurityHeaders(exchange);
        headers.set("Content-Type", contentType(fileName));
        headers.set("Content-Disposition", contentDisposition(fileName));
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "private, no-store");
        headers.set("Content-Length", Long.toString(length));
        if (range != null) {
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
        }

        if (headOnly) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(status, length);
        try (OutputStream output = exchange.getResponseBody();
             FileChannel channel = FileChannel.open(target, StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read < 0) break;
                buffer.flip();
                output.write(buffer.array(), 0, read);
                remaining -= read;
            }
        } finally {
            exchange.close();
        }
    }

    private Path resolveFile(String fileName) {
        Path resolved = storageDirectory.resolve(fileName).normalize();
        if (!resolved.getParent().equals(storageDirectory)) {
            throw new IllegalArgumentException("nested paths are not allowed");
        }
        return resolved;
    }

    private static String decodeAndValidateFileName(String rawName) {
        String decoded;
        try {
            decoded = URLDecoder.decode(rawName.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("file name is not valid URL encoding", ex);
        }
        if (decoded.isBlank()) throw new IllegalArgumentException("file name is required");
        if (decoded.length() > 180) throw new IllegalArgumentException("file name is too long");
        if (decoded.equals(".") || decoded.equals("..") || decoded.startsWith(".")) {
            throw new IllegalArgumentException("hidden or relative file names are not allowed");
        }
        for (int i = 0; i < decoded.length(); i++) {
            char ch = decoded.charAt(i);
            if (ch < 32 || ch == 127 || ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|') {
                throw new IllegalArgumentException("file name contains unsupported characters");
            }
        }
        return decoded;
    }

    private FileMetadata metadata(Path path) throws IOException {
        return new FileMetadata(
                path.getFileName().toString(),
                Files.size(path),
                sha256(path),
                Files.getLastModifiedTime(path).toInstant());
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ByteRange parseRange(String header, long fileSize) {
        if (header == null || header.isBlank()) return null;
        if (!header.startsWith("bytes=") || header.contains(",") || fileSize == 0) {
            throw new IllegalArgumentException("unsupported range");
        }
        String value = header.substring("bytes=".length()).trim();
        int dash = value.indexOf('-');
        if (dash < 0) throw new IllegalArgumentException("invalid range");
        String startText = value.substring(0, dash).trim();
        String endText = value.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) throw new IllegalArgumentException("invalid suffix range");
                suffixLength = Math.min(suffixLength, fileSize);
                start = fileSize - suffixLength;
                end = fileSize - 1;
            } else {
                start = Long.parseLong(startText);
                if (start < 0 || start >= fileSize) throw new IllegalArgumentException("range start outside file");
                end = endText.isEmpty() ? fileSize - 1 : Long.parseLong(endText);
                if (end < start) throw new IllegalArgumentException("range end before start");
                end = Math.min(end, fileSize - 1);
            }
            return new ByteRange(start, end);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid range number", ex);
        }
    }

    private static String contentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".gz") || lower.endsWith(".tgz")) return "application/gzip";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String contentDisposition(String fileName) {
        String fallback = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (fallback.isBlank()) fallback = "download";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        applySecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void applySecurityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
                    else result.append(ch);
                }
            }
        }
        return result.toString();
    }

    private record FileMetadata(String name, long size, String sha256, Instant updatedAt) {
        String toJson() {
            return "{\"name\":\"" + jsonEscape(name)
                    + "\",\"size\":" + size
                    + ",\"sha256\":\"" + sha256
                    + "\",\"updatedAt\":\"" + jsonEscape(updatedAt.toString())
                    + "\",\"downloadUrl\":\"/files/" + pathEncode(name) + "\"}";
        }
    }

    private static String pathEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record ByteRange(long start, long end) {
    }

    private static final class UploadTooLargeException extends RuntimeException {
    }

    private static final class MetadataReadException extends RuntimeException {
        private final IOException ioException;

        private MetadataReadException(IOException cause) {
            super(cause);
            this.ioException = cause;
        }
    }
}
