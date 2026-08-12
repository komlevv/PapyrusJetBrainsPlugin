package dev.papyrus.jetbrains.actions;

import dev.papyrus.jetbrains.protocol.DocumentScriptInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SearchCreationKitWikiActionBehaviorTest {

    @Test
    void buildsSkyrimSearchUrlWithStableUtf8Escaping() {
        assertEquals(
                "https://www.creationkit.com/index.php?search=SharedProbe",
                SearchCreationKitWikiAction.buildSkyrimSearchUrl("SharedProbe")
        );
        assertEquals(
                "https://www.creationkit.com/index.php?search=Debug.Notification%28%22wiki%20search%20%26%20value%22%29",
                SearchCreationKitWikiAction.buildSkyrimSearchUrl("Debug.Notification(\"wiki search & value\")")
        );
    }

    @Test
    void explicitSelectionNeverFallsBackToCaretWord() {
        assertEquals(
                "  Shared Probe  ",
                PapyrusActionUtil.chooseSearchText(true, "  Shared Probe  ", "CaretWord")
        );
        assertNull(PapyrusActionUtil.chooseSearchText(true, "first\nsecond", "CaretWord"));
        assertNull(PapyrusActionUtil.chooseSearchText(true, "   ", "CaretWord"));
        assertEquals("CaretWord", PapyrusActionUtil.chooseSearchText(false, null, "CaretWord"));
        assertNull(PapyrusActionUtil.chooseSearchText(false, null, null));
    }

    @Test
    void unresolvedScriptStatusBlocksExternalSearch() {
        assertFalse(SearchCreationKitWikiAction.isResolved(null));
        assertFalse(SearchCreationKitWikiAction.isResolved(new DocumentScriptInfo()));

        DocumentScriptInfo resolved = new DocumentScriptInfo();
        resolved.setIdentifiers(List.of("FeatureTarget"));
        assertTrue(SearchCreationKitWikiAction.isResolved(resolved));
    }
}
