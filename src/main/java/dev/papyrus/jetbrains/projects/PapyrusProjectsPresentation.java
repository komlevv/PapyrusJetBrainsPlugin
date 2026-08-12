package dev.papyrus.jetbrains.projects;

import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoScript;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class PapyrusProjectsPresentation {

    static final int SCRIPT_GROUP_THRESHOLD = 400;

    private static final Comparator<ProjectInfoSourceInclude> INCLUDE_ORDER =
            Comparator.comparing(PapyrusProjectsPresentation::includeSortKey, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PapyrusProjectsPresentation::includeSortKey);

    private static final Comparator<ProjectInfoScript> SCRIPT_ORDER =
            Comparator.comparing(PapyrusProjectsPresentation::scriptSortKey, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PapyrusProjectsPresentation::scriptSortKey);

    private PapyrusProjectsPresentation() {
    }

    static @NotNull List<ProjectInfoSourceInclude> localIncludes(@NotNull ProjectInfo projectInfo) {
        return projectInfo.getSourceIncludes().stream()
                .filter(include -> !include.isImport())
                .sorted(INCLUDE_ORDER)
                .toList();
    }

    static @NotNull List<ProjectInfoSourceInclude> importIncludes(@NotNull ProjectInfo projectInfo) {
        return projectInfo.getSourceIncludes().stream()
                .filter(ProjectInfoSourceInclude::isImport)
                .sorted(INCLUDE_ORDER)
                .toList();
    }

    static @NotNull List<ProjectInfoScript> sortedScripts(@NotNull List<ProjectInfoScript> scripts) {
        return scripts.stream().sorted(SCRIPT_ORDER).toList();
    }

    static boolean requiresScriptGrouping(@NotNull List<ProjectInfoScript> scripts) {
        return scripts.size() > SCRIPT_GROUP_THRESHOLD;
    }

    static @NotNull Map<String, List<ProjectInfoScript>> groupScripts(@NotNull List<ProjectInfoScript> scripts) {
        Map<String, List<ProjectInfoScript>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ProjectInfoScript script : scripts) {
            groups.computeIfAbsent(scriptGroup(script), ignored -> new java.util.ArrayList<>()).add(script);
        }
        groups.replaceAll((ignored, groupedScripts) -> sortedScripts(groupedScripts));
        return groups;
    }

    static @NotNull String formatIncludeLabel(@NotNull ProjectInfoSourceInclude include) {
        String name = nonBlank(include.getName(), include.isImport() ? "Import" : "Source");
        String fullPath = include.getFullPath();
        String label = name;

        if (fullPath != null && !fullPath.isBlank()) {
            label = name + ": " + leafName(fullPath);
        }

        return include.isRemote() ? label + " [remote]" : label;
    }

    private static @NotNull String leafName(@NotNull String path) {
        int end = path.length();
        while (end > 1 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        String trimmed = path.substring(0, end);
        int separator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return separator >= 0 && separator + 1 < trimmed.length()
                ? trimmed.substring(separator + 1)
                : trimmed;
    }

    private static @NotNull String scriptGroup(@NotNull ProjectInfoScript script) {
        String identifier = script.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            return "#";
        }
        char first = Character.toUpperCase(identifier.charAt(0));
        return Character.isLetterOrDigit(first) ? Character.toString(first) : "#";
    }

    private static @NotNull String includeSortKey(@NotNull ProjectInfoSourceInclude include) {
        return formatIncludeLabel(include);
    }

    private static @NotNull String scriptSortKey(@NotNull ProjectInfoScript script) {
        return nonBlank(script.getIdentifier(), "Script");
    }

    private static @NotNull String nonBlank(String value, @NotNull String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
