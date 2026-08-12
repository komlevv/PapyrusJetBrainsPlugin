package dev.papyrus.jetbrains.run;

import com.intellij.openapi.project.Project;
import com.intellij.task.ModuleBuildTask;
import com.intellij.task.ProjectTask;
import dev.papyrus.jetbrains.config.PapyrusProjectSettings;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusProjectTaskRunnerBehaviorTest {

    @Test
    void buildRunnerIsCompletelyOptInAndOnlyHandlesModuleBuildTasks() {
        PapyrusProjectSettings projectSettings = new PapyrusProjectSettings();
        Project project = projectProxy(projectSettings);
        ModuleBuildTask moduleBuildTask = proxy(ModuleBuildTask.class);
        ProjectTask unrelatedTask = proxy(ProjectTask.class);
        PapyrusProjectTaskRunner runner = new PapyrusProjectTaskRunner();

        assertFalse(runner.canRun(project, moduleBuildTask, null));

        projectSettings.getState().buildSystem = PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS;
        assertTrue(runner.canRun(project, moduleBuildTask, null));
        assertFalse(runner.canRun(project, unrelatedTask, null));

        projectSettings.getState().buildSystem = PapyrusProjectSettings.BUILD_SYSTEM_IDE;
        assertFalse(runner.canRun(project, moduleBuildTask, null));
    }

    private static Project projectProxy(PapyrusProjectSettings projectSettings) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getService" -> args != null && args.length == 1 && args[0] == PapyrusProjectSettings.class
                            ? projectSettings
                            : null;
                    case "isDisposed", "isDefault" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PapyrusProjectTaskRunnerBehaviorTestProject";
                    default -> null;
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName() + "Proxy";
                    case "isIncrementalBuild", "isIncludeDependentModules", "isIncludeRuntimeDependencies", "isIncludeTests" -> true;
                    default -> null;
                }
        );
    }
}
