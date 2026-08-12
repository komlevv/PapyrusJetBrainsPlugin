package dev.papyrus.jetbrains.actions;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class PapyrusActionTestBridge {
    private static final String UI_TEST_PROPERTY = "papyrus.ui.integration.test";
    private static final AtomicReference<ProjectGenerationRequest> PROJECT_GENERATION_REQUEST = new AtomicReference<>();
    private static final AtomicReference<CapturedMessage> CAPTURED_MESSAGE = new AtomicReference<>();
    private static final AtomicReference<String> PAPYRUS_COMPILE_SELECTION = new AtomicReference<>();

    private PapyrusActionTestBridge() {
    }

    public static void prepareProjectGeneration(@NotNull String parentDirectory, @NotNull String folderName) {
        requireEnabled();
        PROJECT_GENERATION_REQUEST.set(new ProjectGenerationRequest(
                Path.of(parentDirectory).toAbsolutePath().normalize(),
                folderName,
                false
        ));
    }

    public static void cancelProjectGeneration() {
        requireEnabled();
        PROJECT_GENERATION_REQUEST.set(new ProjectGenerationRequest(null, null, true));
    }

    static @Nullable ProjectGenerationRequest consumeProjectGenerationRequest() {
        requireEnabled();
        return PROJECT_GENERATION_REQUEST.getAndSet(null);
    }


    public static void preparePapyrusCompileSelection(@NotNull String projectFile) {
        requireEnabled();
        PAPYRUS_COMPILE_SELECTION.set(projectFile.replace('\\', '/'));
    }

    static @Nullable String consumePapyrusCompileSelection() {
        requireEnabled();
        return PAPYRUS_COMPILE_SELECTION.getAndSet(null);
    }

    public static void clearPapyrusCompileSelection() {
        requireEnabled();
        PAPYRUS_COMPILE_SELECTION.set(null);
    }

    static boolean captureMessageIfEnabled(@NotNull String kind, @NotNull String message) {
        if (!Boolean.getBoolean(UI_TEST_PROPERTY)) {
            return false;
        }
        CAPTURED_MESSAGE.set(new CapturedMessage(kind, message));
        return true;
    }

    public static void clearCapturedMessage() {
        requireEnabled();
        CAPTURED_MESSAGE.set(null);
    }

    public static @Nullable String capturedMessageKind() {
        requireEnabled();
        CapturedMessage message = CAPTURED_MESSAGE.get();
        return message == null ? null : message.kind();
    }

    public static @Nullable String capturedMessageText() {
        requireEnabled();
        CapturedMessage message = CAPTURED_MESSAGE.get();
        return message == null ? null : message.text();
    }

    static boolean isUiIntegrationTest() {
        return Boolean.getBoolean(UI_TEST_PROPERTY);
    }

    private static void requireEnabled() {
        if (!Boolean.getBoolean(UI_TEST_PROPERTY)) {
            throw new IllegalStateException("Papyrus action test bridge is disabled outside integration tests");
        }
    }

    record ProjectGenerationRequest(@Nullable Path parentDirectory, @Nullable String folderName, boolean cancelled) {
    }

    private record CapturedMessage(@NotNull String kind, @NotNull String text) {
    }
}
