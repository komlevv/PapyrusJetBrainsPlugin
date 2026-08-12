package dev.papyrus.jetbrains.lsp;

import com.intellij.platform.lsp.api.LspServerNotificationsHandler;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
