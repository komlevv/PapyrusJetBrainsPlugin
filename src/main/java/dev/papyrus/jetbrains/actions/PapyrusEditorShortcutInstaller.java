package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

/**
 * Installs Papyrus-only editor shortcuts without changing the global keymap.
 */
public final class PapyrusEditorShortcutInstaller implements EditorFactoryListener {

    private static final Key<AnAction> GOTO_DECLARATION_SHORTCUT_ACTION =
            Key.create("dev.papyrus.jetbrains.gotoDeclarationShortcutAction");

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        if (!PapyrusActionUtil.isExtension(editor.getVirtualFile(), "psc")) {
            return;
        }

        AnAction platformAction = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION);
        if (platformAction == null) {
            return;
        }

        AnAction localAction = new PapyrusGotoDeclarationShortcutAction(editor, platformAction);
        localAction.registerCustomShortcutSet(
                platformAction.getShortcutSet(),
                editor.getContentComponent()
        );
        editor.putUserData(GOTO_DECLARATION_SHORTCUT_ACTION, localAction);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        AnAction localAction = editor.getUserData(GOTO_DECLARATION_SHORTCUT_ACTION);
        if (localAction == null) {
            return;
        }

        localAction.unregisterCustomShortcutSet(editor.getContentComponent());
        editor.putUserData(GOTO_DECLARATION_SHORTCUT_ACTION, null);
    }

    private static final class PapyrusGotoDeclarationShortcutAction extends DumbAwareAction {
        private final Editor editor;
        private final AnAction platformAction;

        private PapyrusGotoDeclarationShortcutAction(@NotNull Editor editor, @NotNull AnAction platformAction) {
            this.editor = editor;
            this.platformAction = platformAction;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            if (editor.isDisposed() || !PapyrusActionUtil.isExtension(editor.getVirtualFile(), "psc")) {
                return;
            }

            // The asynchronous branch rebuilds editor context without reusing the physical shortcut event.
            ActionManager.getInstance().tryToExecute(
                    platformAction,
                    null,
                    editor.getContentComponent(),
                    null,
                    false
            );
        }
    }
}
