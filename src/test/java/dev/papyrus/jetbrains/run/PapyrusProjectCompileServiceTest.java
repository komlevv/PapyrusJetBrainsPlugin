package dev.papyrus.jetbrains.run;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusProjectCompileServiceTest {

    @Test
    void compileEntryPointsShareOneProjectLevelExecutionGate() {
        Project project = (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PapyrusProjectCompileServiceTestProject";
                    default -> null;
                }
        );

        assertTrue(PapyrusProjectCompileService.tryAcquire(project));
        assertTrue(PapyrusProjectCompileService.isRunning(project));
        assertFalse(PapyrusProjectCompileService.tryAcquire(project));

        PapyrusProjectCompileService.release(project);
        assertFalse(PapyrusProjectCompileService.isRunning(project));
        assertTrue(PapyrusProjectCompileService.tryAcquire(project));
        PapyrusProjectCompileService.release(project);
    }
}
