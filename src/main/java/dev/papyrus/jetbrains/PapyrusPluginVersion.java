package dev.papyrus.jetbrains;

/**
 * Version marker for project-local Papyrus state stored under .idea.
 *
 * <p>Keep this value in sync with build.gradle.kts and META-INF/plugin.xml.
 * PluginDescriptorBehaviorTest guards the packaged descriptor value.</p>
 */
public final class PapyrusPluginVersion {
    public static final String CURRENT = "0.2.174";

    private PapyrusPluginVersion() {
    }
}
