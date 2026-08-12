package dev.papyrus.jetbrains.status;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, read-only Papyrus tool output for IntelliJ Platform 2026.2.
 *
 * <p>The target 262 platform does not expose LSP clients through Services. This service provides one
 * bounded session transcript for language-service diagnostics and explicitly invoked Papyrus tools
 * such as safe project compilation, without writing a plugin log into game or source locations.</p>
 */
@Service(Service.Level.PROJECT)
public final class PapyrusLspOutputService {
    private static final int MAX_CHARS = 256 * 1024;

    private final Project project;
    private final Object lock = new Object();
    private final StringBuilder text = new StringBuilder();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public PapyrusLspOutputService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PapyrusLspOutputService getInstance(@NotNull Project project) {
        return project.getService(PapyrusLspOutputService.class);
    }

    public void appendLine(@NotNull String line) {
        synchronized (lock) {
            text.append(line).append('\n');
            if (text.length() > MAX_CHARS) {
                int remove = text.length() - MAX_CHARS;
                int newline = text.indexOf("\n", remove);
                text.delete(0, newline >= 0 ? newline + 1 : remove);
            }
        }
        notifyListeners();
    }

    public void appendBlankLine() {
        appendLine("");
    }

    public @NotNull String snapshot() {
        synchronized (lock) {
            return text.toString();
        }
    }

    public void addListener(@NotNull Runnable listener, @NotNull Disposable parentDisposable) {
        listeners.add(listener);
        Disposer.register(parentDisposable, () -> listeners.remove(listener));
    }

    private void notifyListeners() {
        if (project.isDisposed() || listeners.isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }
}
