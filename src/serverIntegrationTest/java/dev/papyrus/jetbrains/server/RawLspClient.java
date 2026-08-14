package dev.papyrus.jetbrains.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RawLspClient implements AutoCloseable {
    private static final Pattern ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD = Pattern.compile("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern EMPTY_DIAGNOSTICS = Pattern.compile("\\\"diagnostics\\\"\\s*:\\s*\\[\\s*\\]");

    enum CapabilityProfile {
        DYNAMIC_TEST_CLIENT,
        JETBRAINS_262
    }

    record ServerRequest(int id, String method, String json) {}
    record ServerNotification(String method, String json) {}

    private final Process process;
    private final InputStream input;
    private final OutputStream output;
    private final String rootUri;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<String>> responses = new ConcurrentHashMap<>();
    private final BlockingQueue<ServerRequest> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<ServerNotification> notifications = new LinkedBlockingQueue<>();
    private final Thread reader;
    private volatile boolean closed;

    RawLspClient(Process process, String rootUri) {
        this.process = process;
        this.input = new BufferedInputStream(process.getInputStream());
        this.output = new BufferedOutputStream(process.getOutputStream());
        this.rootUri = rootUri;
        this.reader = Thread.ofPlatform().daemon(true).name("papyrus-lsp-test-reader").start(this::readLoop);
    }

    String initialize() throws Exception {
        return initialize(CapabilityProfile.DYNAMIC_TEST_CLIENT);
    }

    String initialize(CapabilityProfile profile) throws Exception {
        String definitionCapabilities = profile == CapabilityProfile.JETBRAINS_262
                ? "\"definition\":{\"linkSupport\":true},"
                : "\"definition\":{\"dynamicRegistration\":true},";
        String response = request("initialize", "{"
                + "\"processId\":null,"
                + "\"rootUri\":" + json(rootUri) + ","
                + "\"workspaceFolders\":[{\"uri\":" + json(rootUri) + ",\"name\":\"Papyrus Test\"}],"
                + "\"capabilities\":{"
                + "\"workspace\":{\"applyEdit\":false,\"didChangeWatchedFiles\":{\"dynamicRegistration\":false}},"
                + "\"textDocument\":{"
                + "\"completion\":{\"dynamicRegistration\":true},"
                + definitionCapabilities
                + "\"hover\":{\"dynamicRegistration\":true},"
                + "\"signatureHelp\":{\"dynamicRegistration\":true},"
                + "\"references\":{\"dynamicRegistration\":true},"
                + "\"documentSymbol\":{\"dynamicRegistration\":true,\"hierarchicalDocumentSymbolSupport\":true}"
                + "}}}", Duration.ofSeconds(45));
        notify("initialized", "{}");
        return response;
    }

    String request(String method, String params, Duration timeout) throws Exception {
        int id = nextId.getAndIncrement();
        CompletableFuture<String> future = new CompletableFuture<>();
        responses.put(id, future);
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":" + json(method) + ",\"params\":" + params + "}");
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new AssertionError("Timed out waiting for " + method, error);
        } finally {
            responses.remove(id);
        }
    }

    void notify(String method, String params) throws IOException {
        send("{\"jsonrpc\":\"2.0\",\"method\":" + json(method)
                + (params == null ? "" : ",\"params\":" + params) + "}");
    }

    void didOpen(String uri, String text) throws IOException {
        notify("textDocument/didOpen", "{\"textDocument\":{\"uri\":" + json(uri)
                + ",\"languageId\":\"papyrus\",\"version\":1,\"text\":" + json(text) + "}}");
    }

    void didChange(String uri, Position start, Position end, int rangeLength, String text) throws IOException {
        didChange(uri, 2, start, end, rangeLength, text);
    }

    void didChange(String uri, int version, Position start, Position end, int rangeLength, String text) throws IOException {
        notify("textDocument/didChange", "{\"textDocument\":{\"uri\":" + json(uri) + ",\"version\":" + version + "},"
                + "\"contentChanges\":[{\"range\":{\"start\":" + start.json() + ",\"end\":" + end.json() + "},"
                + "\"rangeLength\":" + rangeLength + ",\"text\":" + json(text) + "}]}");
    }

    void didClose(String uri) throws IOException {
        notify("textDocument/didClose", "{\"textDocument\":{\"uri\":" + json(uri) + "}}");
    }


    ServerRequest awaitReferencesRegistration(Duration timeout) throws InterruptedException {
        return awaitRegistration("textDocument/references", timeout);
    }

    ServerRequest awaitDefinitionRegistration(Duration timeout) throws InterruptedException {
        return awaitRegistration("textDocument/definition", timeout);
    }

    private ServerRequest awaitRegistration(String capability, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            ServerRequest request = requests.poll(remaining, TimeUnit.NANOSECONDS);
            if (request == null) return null;
            if ("client/registerCapability".equals(request.method()) && request.json().contains(capability)) return request;
        }
    }

    String awaitPublishedDiagnostics(String uri, boolean expectEmpty, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String encodedUri = json(uri);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            ServerNotification notification = notifications.poll(remaining, TimeUnit.NANOSECONDS);
            if (notification == null) return null;
            if (!"textDocument/publishDiagnostics".equals(notification.method())) continue;
            String json = notification.json();
            if (!json.contains(encodedUri)) continue;
            if (EMPTY_DIAGNOSTICS.matcher(json).find() == expectEmpty) return json;
        }
    }

    void respond(int id, String resultJson) throws IOException {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
    }

    void shutdown() throws Exception {
        request("shutdown", "null", Duration.ofSeconds(15));
        notify("exit", null);
        process.waitFor(10, TimeUnit.SECONDS);
    }

    private void readLoop() {
        try {
            while (!closed) {
                String message = readFrame(input);
                Integer id = extractId(message);
                String method = extractMethod(message);
                if (id != null && method != null && !message.contains("\"result\"") && !message.contains("\"error\"")) {
                    ServerRequest request = new ServerRequest(id, method, message);
                    requests.add(request);
                    autoRespond(request);
                } else if (id != null) {
                    CompletableFuture<String> future = responses.get(id);
                    if (future != null) future.complete(message);
                } else if (method != null) {
                    notifications.add(new ServerNotification(method, message));
                }
            }
        } catch (Exception error) {
            if (!closed) responses.values().forEach(future -> future.completeExceptionally(error));
        }
    }

    private void autoRespond(ServerRequest request) throws IOException {
        String result = switch (request.method()) {
            case "workspace/workspaceFolders" -> "[{\"uri\":" + json(rootUri) + ",\"name\":\"Papyrus Test\"}]";
            case "workspace/configuration" -> "[]";
            case "workspace/applyEdit" -> "{\"applied\":false,\"failureReason\":\"tests are read-only\"}";
            case "window/showDocument" -> "{\"success\":false}";
            default -> "null";
        };
        respond(request.id(), result);
    }

    private synchronized void send(String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static String readFrame(InputStream input) throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readAsciiLine(input);
            if (line == null) throw new EOFException();
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0 && "Content-Length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) throw new IOException("Missing Content-Length");
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) throw new EOFException();
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.US_ASCII);
            if (value == '\n') break;
            if (value != '\r') buffer.write(value);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private static Integer extractId(String value) {
        Matcher matcher = ID.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static String extractMethod(String value) {
        Matcher matcher = METHOD.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    static Position position(String text, int offset) {
        int line = 0;
        int character = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new Position(line, character);
    }

    static String json(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    @Override
    public void close() {
        closed = true;
        reader.interrupt();
        try { output.close(); } catch (IOException ignored) {}
        try { input.close(); } catch (IOException ignored) {}
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    record Position(int line, int character) {
        String json() { return "{\"line\":" + line + ",\"character\":" + character + "}"; }
    }
}
