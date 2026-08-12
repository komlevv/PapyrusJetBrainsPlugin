package dev.papyrus.jetbrains.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import org.jetbrains.annotations.NotNull;

final class PapyrusProjectCommandLineState extends CommandLineState {
    private final PapyrusProjectRunConfiguration configuration;

    PapyrusProjectCommandLineState(
            @NotNull ExecutionEnvironment environment,
            @NotNull PapyrusProjectRunConfiguration configuration
    ) {
        super(environment);
        this.configuration = configuration;
        addConsoleFilters(new PapyrusCompilerFilter(configuration.getProject()));
    }

    @Override
    protected @NotNull ProcessHandler startProcess() throws ExecutionException {
        var project = configuration.getProject();
        if (!PapyrusProjectCompileService.tryAcquire(project)) {
            throw new ExecutionException("A Papyrus project compilation is already running.");
        }

        try {
            PapyrusProjectCompileService.PreparedCompile prepared = PapyrusProjectCompileService.prepare(
                    project,
                    configuration.resolveProjectFile()
            );
            return PapyrusProjectCompileService.startProcess(project, prepared);
        } catch (ExecutionException exception) {
            PapyrusProjectCompileService.release(project);
            throw exception;
        } catch (Exception exception) {
            PapyrusProjectCompileService.release(project);
            throw new ExecutionException(safeMessage(exception), exception);
        }
    }

    private static @NotNull String safeMessage(@NotNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
