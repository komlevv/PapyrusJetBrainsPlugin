package dev.papyrus.jetbrains.actions;

import com.intellij.ide.BrowserUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

public final class PapyrusExternalUrlOpener {
    private static final String UI_TEST_PROPERTY = "papyrus.ui.integration.test";
    private static final AtomicReference<String> CAPTURED_TEST_URL = new AtomicReference<>();

    private PapyrusExternalUrlOpener() {
    }

    public static void open(@NotNull String url) {
        if (Boolean.getBoolean(UI_TEST_PROPERTY)) {
            CAPTURED_TEST_URL.set(url);
            return;
        }
        BrowserUtil.browse(url);
    }

    public static void clearCapturedUrlForTests() {
        requireUiTestMode();
        CAPTURED_TEST_URL.set(null);
    }

    public static @Nullable String capturedUrlForTests() {
        requireUiTestMode();
        return CAPTURED_TEST_URL.get();
    }

    private static void requireUiTestMode() {
        if (!Boolean.getBoolean(UI_TEST_PROPERTY)) {
            throw new IllegalStateException("External URL capture is available only in Papyrus UI integration tests");
        }
    }
}
