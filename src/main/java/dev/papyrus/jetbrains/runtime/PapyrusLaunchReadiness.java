package dev.papyrus.jetbrains.runtime;

import org.jetbrains.annotations.NotNull;

public record PapyrusLaunchReadiness(@NotNull Kind kind, @NotNull String detail) {
    public enum Kind {
        DISABLED,
        READY,
        MISSING_GAME,
        COMPILER_MISSING,
        ERROR
    }

    public static @NotNull PapyrusLaunchReadiness disabled() {
        return new PapyrusLaunchReadiness(Kind.DISABLED, "Papyrus language service is disabled in Settings | Papyrus.");
    }

    public static @NotNull PapyrusLaunchReadiness ready() {
        return new PapyrusLaunchReadiness(Kind.READY, "");
    }

    public static @NotNull PapyrusLaunchReadiness missingGame(@NotNull String detail) {
        return new PapyrusLaunchReadiness(Kind.MISSING_GAME, detail);
    }

    public static @NotNull PapyrusLaunchReadiness compilerMissing(@NotNull String detail) {
        return new PapyrusLaunchReadiness(Kind.COMPILER_MISSING, detail);
    }

    public static @NotNull PapyrusLaunchReadiness error(@NotNull String detail) {
        return new PapyrusLaunchReadiness(Kind.ERROR, detail);
    }
}
