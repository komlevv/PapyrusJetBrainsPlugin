package dev.papyrus.jetbrains.projects;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusImportProjectViewDecoratorTest {

    @Test
    void importLabelIsRestrictedToPapyrusSyntheticLibraryParent() {
        PapyrusImportSyntheticLibrary papyrusLibrary = new PapyrusImportSyntheticLibrary(
                List.of(),
                Map.of()
        );

        assertTrue(PapyrusImportProjectViewDecorator.isPapyrusImportLibraryParent(papyrusLibrary));
        assertFalse(PapyrusImportProjectViewDecorator.isPapyrusImportLibraryParent("Papyrus Imports"));
        assertFalse(PapyrusImportProjectViewDecorator.isPapyrusImportLibraryParent(null));
    }
}
