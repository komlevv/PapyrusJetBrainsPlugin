package dev.papyrus.jetbrains.debug;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class DapConnection implements AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final Process process;
    private final BufferedInputStream input;
    private final OutputStream output;
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean readersStarted = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private volatile BiConsumer<String, JsonObject> eventHandler = (event, body) -> { };
    private volatile Consumer<String> stderrHandler = text -> { };
    private volatile Runnable closeHandler = () -> { };

    private DapConnection(@NotNull Process process) {
        this.process = process;
        this.input = new BufferedInputStream(process.getInputStream());
        this.output = process.getOutputStream();
    }

    static @NotNull DapConnection start(@NotNull List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (!command.isEmpty()) {
            try {
                Path executable = Path.of(command.getFirst()).toAbsolutePath().normalize();
                Path parent = executable.getParent();
                if (parent != null) {
                    builder.directory(parent.toFile());
                }
            } catch (RuntimeException ignored) {
            }
        }
        builder.redirectErrorStream(false);
        return new DapConnection(builder.start());
    }

    void setEventHandler(@NotNull BiConsumer<String, JsonObject> handler) {
        eventHandler = handler;
    }

    void setStderrHandler(@NotNull Consumer<String> handler) {
        stderrHandler = handler;
    }

    void setCloseHandler(@NotNull Runnable handler) {
        closeHandler = handler;
    }

    void startReading() {
        if (readersStarted.compareAndSet(false, true)) {
            startReader();
            startStderrReader();
        }
    }

    boolean isAlive() {
        return process.isAlive() && !closed.get();
    }

    @NotNull CompletableFuture<JsonObject> request(@NotNull String command, JsonObject arguments) {
        int requestSequence = sequence.getAndIncrement();
        JsonObject request = new JsonObject();
        request.addProperty("seq", requestSequence);
        request.addProperty("type", "request");
        request.addProperty("command", command);
        if (arguments != null) {
            request.add("arguments", arguments);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(requestSequence, future);
        try {
            send(request);
        } catch (IOException exception) {
            pending.remove(requestSequence);
            future.completeExceptionally(exception);
        }
        future.orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((ignored, error) -> pending.remove(requestSequence, future));
        return future;
    }

    private void startReader() {
        Thread thread = new Thread(() -> {
            try {
                while (!closed.get()) {
                    JsonObject message = readMessage();
                    if (message == null) {
                        break;
                    }
                    dispatch(message);
                }
            } catch (Exception throwable) {
                if (!closed.get()) {
                    failPending(throwable);
                }
            } finally {
                if (!closed.get()) {
                    failPending(new IOException("Papyrus debug adapter connection closed."));
                    closeHandler.run();
                }
            }
        }, "Papyrus DAP reader");
        thread.setDaemon(true);
        thread.start();
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrHandler.accept(line + System.lineSeparator());
                }
            } catch (IOException ignored) {
            }
        }, "Papyrus DAP stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void dispatch(@NotNull JsonObject message) throws IOException {
        String type = string(message, "type");
        if ("response".equals(type)) {
            int requestSequence = integer(message, "request_seq", -1);
            CompletableFuture<JsonObject> future = pending.remove(requestSequence);
            if (future == null) {
                return;
            }
            if (booleanValue(message, "success", true)) {
                JsonObject body = object(message, "body");
                future.complete(body != null ? body : new JsonObject());
            } else {
                String command = string(message, "command");
                String messageText = string(message, "message");
                future.completeExceptionally(new IOException(
                        "DAP request failed" + (command != null ? " (" + command + ")" : "")
                                + (messageText != null ? ": " + messageText : ".")
                ));
            }
            return;
        }

        if ("event".equals(type)) {
            String event = string(message, "event");
            if (event != null) {
                JsonObject body = object(message, "body");
                eventHandler.accept(event, body != null ? body : new JsonObject());
            }
            return;
        }

        if ("request".equals(type)) {
            sendUnsupportedResponse(message);
        }
    }

    private void sendUnsupportedResponse(@NotNull JsonObject request) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("seq", sequence.getAndIncrement());
        response.addProperty("type", "response");
        response.addProperty("request_seq", integer(request, "seq", 0));
        response.addProperty("success", false);
        String command = string(request, "command");
        if (command != null) {
            response.addProperty("command", command);
        }
        response.addProperty("message", "This DAP client request is not supported by the Papyrus JetBrains integration.");
        send(response);
    }

    private void send(@NotNull JsonObject message) throws IOException {
        byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            output.write(header);
            output.write(payload);
            output.flush();
        }
    }

    private JsonObject readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readAsciiLine(input);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if ("content-length".equals(name)) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
        }

        if (contentLength < 0) {
            throw new IOException("DAP message did not contain Content-Length.");
        }

        byte[] payload = input.readNBytes(contentLength);
        if (payload.length != contentLength) {
            throw new IOException("Unexpected end of DAP stream.");
        }
        JsonElement parsed = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IOException("DAP message is not a JSON object.");
        }
        return parsed.getAsJsonObject();
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        boolean readAny = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                return readAny ? builder.toString() : null;
            }
            readAny = true;
            if (value == '\n') {
                int length = builder.length();
                if (length > 0 && builder.charAt(length - 1) == '\r') {
                    builder.setLength(length - 1);
                }
                return builder.toString();
            }
            builder.append((char) value);
        }
    }

    private void failPending(Throwable throwable) {
        for (CompletableFuture<JsonObject> future : pending.values()) {
            future.completeExceptionally(throwable);
        }
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failPending(new IOException("Papyrus debug adapter connection closed."));
        try {
            output.close();
        } catch (IOException ignored) {
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    static JsonObject object(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(name);
    }

    static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static int integer(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static boolean booleanValue(JsonObject object, String name, boolean fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
