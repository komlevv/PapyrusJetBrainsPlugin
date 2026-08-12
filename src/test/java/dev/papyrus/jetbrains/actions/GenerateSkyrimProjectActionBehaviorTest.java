package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GenerateSkyrimProjectActionBehaviorTest {

    @Test
    @SuppressWarnings("ConstantValue") // The test intentionally asserts both sides of the null/non-null action contract.
    void actionRequiresAProjectContext() {
        Project project = (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "Papyrus test project";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> args != null && args.length == 1 && proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );

        assertFalse(GenerateSkyrimProjectAction.isEnabledFor(null));
        assertTrue(GenerateSkyrimProjectAction.isEnabledFor(project));
    }
}
