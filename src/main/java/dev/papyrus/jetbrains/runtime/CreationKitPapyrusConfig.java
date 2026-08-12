package dev.papyrus.jetbrains.runtime;

public record CreationKitPapyrusConfig(
        String scriptSourceFolder,
        String additionalImports,
        String compilerFolder
) {
    public static CreationKitPapyrusConfig skyrimSpecialEditionDefaults() {
        return new CreationKitPapyrusConfig(
                ".\\Data\\Source\\Scripts\\",
                null,
                "Papyrus Compiler\\"
        );
    }
}
