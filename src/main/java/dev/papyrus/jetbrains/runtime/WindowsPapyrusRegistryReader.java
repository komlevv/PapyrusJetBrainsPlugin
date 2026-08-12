package dev.papyrus.jetbrains.runtime;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class WindowsPapyrusRegistryReader implements PapyrusRegistryReader {
    public static final WindowsPapyrusRegistryReader INSTANCE = new WindowsPapyrusRegistryReader();

    private WindowsPapyrusRegistryReader() {
    }

    @Override
    public @Nullable String readLocalMachineString(@NotNull String subKey, @NotNull String valueName) {
        if (!isWindows()) {
            return null;
        }
        try {
            return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, subKey, valueName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.regionMatches(true, 0, "Windows", 0, "Windows".length());
    }
}
