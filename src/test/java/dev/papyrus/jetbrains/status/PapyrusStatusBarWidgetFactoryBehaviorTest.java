package dev.papyrus.jetbrains.status;

import com.intellij.platform.lsp.api.LspServerState;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchReadiness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusStatusBarWidgetFactoryBehaviorTest {
    @Test
    void mapsImplementedLspStatesToStatusBarText() {
        assertNull(PapyrusStatusBarWidgetFactory.statusTextForStates(List.of()));
        assertEquals(
                "Papyrus: starting",
                PapyrusStatusBarWidgetFactory.statusTextForStates(List.of(LspServerState.Initializing))
        );
        assertEquals(
                "Papyrus: running",
                PapyrusStatusBarWidgetFactory.statusTextForStates(List.of(LspServerState.Running))
        );
        assertEquals(
                "Papyrus: error",
                PapyrusStatusBarWidgetFactory.statusTextForStates(List.of(LspServerState.ShutdownUnexpectedly))
        );
        assertEquals(
                "Papyrus: idle",
                PapyrusStatusBarWidgetFactory.statusTextForStates(List.of(LspServerState.ShutdownNormally))
        );
        assertEquals(
                "Papyrus: running",
                PapyrusStatusBarWidgetFactory.statusTextForStates(
                        List.of(LspServerState.Initializing, LspServerState.ShutdownUnexpectedly, LspServerState.Running)
                )
        );


        PapyrusStatusBarWidgetFactory.StatusPresentation running =
                PapyrusStatusBarWidgetFactory.statusPresentationFor(
                        PapyrusLaunchReadiness.ready(),
                        List.of(LspServerState.Running)
                );
        assertNotNull(running);
        assertEquals(PapyrusStatusBarWidgetFactory.ClickAction.OUTPUT, running.clickAction());
        assertEquals(0, PapyrusLspOutputOpener.statePreference(LspServerState.Running));
        assertEquals(1, PapyrusLspOutputOpener.statePreference(LspServerState.Initializing));
        assertEquals(2, PapyrusLspOutputOpener.statePreference(LspServerState.ShutdownUnexpectedly));
        assertEquals(3, PapyrusLspOutputOpener.statePreference(LspServerState.ShutdownNormally));
    }
    @Test
    void launchReadinessOverridesLifecycleStateWithActionableStatus() {
        assertNull(PapyrusStatusBarWidgetFactory.statusPresentationFor(
                PapyrusLaunchReadiness.disabled(),
                List.of(LspServerState.Running)
        ));

        PapyrusStatusBarWidgetFactory.StatusPresentation missingGame =
                PapyrusStatusBarWidgetFactory.statusPresentationFor(
                        PapyrusLaunchReadiness.missingGame("Skyrim Special Edition installation was not found."),
                        List.of(LspServerState.Running)
                );
        assertNotNull(missingGame);
        assertEquals("Papyrus: game missing", missingGame.text());
        assertEquals("Skyrim Special Edition installation was not found.", missingGame.tooltip());
        assertEquals(PapyrusStatusBarWidgetFactory.ClickAction.SETTINGS, missingGame.clickAction());

        PapyrusStatusBarWidgetFactory.StatusPresentation compilerMissing =
                PapyrusStatusBarWidgetFactory.statusPresentationFor(
                        PapyrusLaunchReadiness.compilerMissing("Papyrus Compiler directory was not found: X:/missing"),
                        List.of(LspServerState.Running)
                );
        assertNotNull(compilerMissing);
        assertEquals("Papyrus: compiler missing", compilerMissing.text());
        assertTrue(compilerMissing.tooltip().contains("Settings | Papyrus"));
        assertEquals(PapyrusStatusBarWidgetFactory.ClickAction.SETTINGS, compilerMissing.clickAction());

        PapyrusStatusBarWidgetFactory.StatusPresentation configurationError =
                PapyrusStatusBarWidgetFactory.statusPresentationFor(
                        PapyrusLaunchReadiness.error("VSIX root is missing"),
                        List.of(LspServerState.Running)
                );
        assertNotNull(configurationError);
        assertEquals("Papyrus: error", configurationError.text());
        assertTrue(configurationError.tooltip().contains("VSIX root is missing"));
        assertEquals(PapyrusStatusBarWidgetFactory.ClickAction.OUTPUT, configurationError.clickAction());
    }

    @Test
    void enrichesLifecycleTooltipWithoutChangingCompactStatusText() {
        PapyrusStatusBarWidgetFactory.StatusDetails details =
                new PapyrusStatusBarWidgetFactory.StatusDetails(
                        List.of(LspServerState.Running),
                        1,
                        "DarkId Papyrus",
                        "3.3.0",
                        List.of("X:/workspace"),
                        "FeatureTarget.psc",
                        PapyrusStatusBarWidgetFactory.ScriptActivity.RESOLVED
                );

        PapyrusStatusBarWidgetFactory.StatusPresentation presentation =
                PapyrusStatusBarWidgetFactory.statusPresentationFor(
                        PapyrusLaunchReadiness.ready(),
                        details
                );

        assertNotNull(presentation);
        assertEquals("Papyrus: running", presentation.text());
        assertTrue(presentation.tooltip().contains("Server: DarkId Papyrus 3.3.0."));
        assertTrue(presentation.tooltip().contains("Workspace root: X:/workspace."));
        assertTrue(presentation.tooltip().contains("Current file: FeatureTarget.psc."));
        assertTrue(presentation.tooltip().contains("Script status: resolved."));
        assertEquals(PapyrusStatusBarWidgetFactory.ClickAction.OUTPUT, presentation.clickAction());
    }

}
