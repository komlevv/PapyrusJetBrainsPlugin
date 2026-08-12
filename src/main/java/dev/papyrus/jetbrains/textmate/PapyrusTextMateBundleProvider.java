package dev.papyrus.jetbrains.textmate;

import dev.papyrus.jetbrains.runtime.PapyrusBundledVsix;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PapyrusTextMateBundleProvider implements TextMateBundleProvider {

    @Override
    public @NotNull List<PluginBundle> getBundles() {
        Path extensionRoot;
        try {
            extensionRoot = PapyrusBundledVsix.getExtensionRoot();
        } catch (RuntimeException ignored) {
            return List.of();
        }

        if (!Files.isRegularFile(extensionRoot.resolve("package.json"))) {
            return List.of();
        }

        return List.of(new PluginBundle("Papyrus VSIX", extensionRoot));
    }
}
