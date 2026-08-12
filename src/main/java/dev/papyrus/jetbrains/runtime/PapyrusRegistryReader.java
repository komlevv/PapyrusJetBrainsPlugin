package dev.papyrus.jetbrains.runtime;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface PapyrusRegistryReader {
    @Nullable String readLocalMachineString(@NotNull String subKey, @NotNull String valueName);
}
