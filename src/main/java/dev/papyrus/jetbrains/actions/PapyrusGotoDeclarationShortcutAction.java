package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.ActionWrapperUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

/**
 * Editor-local Papyrus shortcut action that delegates to the platform Go to Declaration action.
 *
 * <p>The action is intentionally not registered in the global action manager. It is attached only
 * to Papyrus editor components and uses the platform GotoDeclaration shortcut set. This keeps
 * user keymap customizations intact while supplying a Papyrus-local frontend candidate before
 * global keymap actions are considered.</p>
 */
final class PapyrusGotoDeclarationShortcutAction extends DumbAwareAction {

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(
                PapyrusActionUtil.isExtension(PapyrusActionUtil.getFile(event), "psc")
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (!PapyrusActionUtil.isExtension(PapyrusActionUtil.getFile(event), "psc")) {
            return;
        }

        AnAction delegate = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION);
        if (delegate == null || delegate == this) {
            return;
        }
        ActionWrapperUtil.actionPerformed(event, this, delegate);
    }
}
