package dev.papyrus.jetbrains.lsp;

import com.intellij.platform.lsp.api.LspServerNotificationsHandler;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PapyrusSafeServerNotificationsHandlerTest {

    @Test
    void unsolicitedWorkspaceApplyEditIsRejectedWithoutDelegating() throws Exception {
        AtomicBoolean delegated = new AtomicBoolean(false);
        LspServerNotificationsHandler delegate = (LspServerNotificationsHandler) Proxy.newProxyInstance(
                LspServerNotificationsHandler.class.getClassLoader(),
                new Class<?>[]{LspServerNotificationsHandler.class},
                (proxy, method, args) -> {
                    if ("applyEdit".equals(method.getName())) {
                        delegated.set(true);
                        ApplyWorkspaceEditResponse response = new ApplyWorkspaceEditResponse();
                        response.setApplied(true);
                        return CompletableFuture.completedFuture(response);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );

        PapyrusSafeServerNotificationsHandler guard = new PapyrusSafeServerNotificationsHandler(delegate);
        ApplyWorkspaceEditResponse response = guard.applyEdit(new ApplyWorkspaceEditParams()).get();

        assertNotNull(response);
        assertFalse(response.isApplied());
        assertFalse(delegated.get());
    }

    @Test
    void validatedWorkspaceFoldersOverrideRealProjectFolders() throws Exception {
        LspServerNotificationsHandler delegate = (LspServerNotificationsHandler) Proxy.newProxyInstance(
                LspServerNotificationsHandler.class.getClassLoader(),
                new Class<?>[]{LspServerNotificationsHandler.class},
                (proxy, method, args) -> {
                    if ("workspaceFolders".equals(method.getName())) {
                        return CompletableFuture.completedFuture(List.of(new WorkspaceFolder("file:///real", "Real")));
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        WorkspaceFolder validated = new WorkspaceFolder("file:///validated", "Papyrus Validated Projects");
        PapyrusSafeServerNotificationsHandler guard = new PapyrusSafeServerNotificationsHandler(
                delegate,
                () -> List.of(validated)
        );

        List<WorkspaceFolder> folders = guard.workspaceFolders().get();

        assertEquals(1, folders.size());
        assertEquals("file:///validated", folders.getFirst().getUri());
        assertEquals("Papyrus Validated Projects", folders.getFirst().getName());
    }

}
