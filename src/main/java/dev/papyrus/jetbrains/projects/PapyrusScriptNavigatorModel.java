package dev.papyrus.jetbrains.projects;

import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoScript;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class PapyrusScriptNavigatorModel {

    static final int DEFAULT_RESULT_LIMIT = 200;

    private static final Comparator<ScriptTarget> TARGET_ORDER =
            Comparator.comparing(ScriptTarget::identifier, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ScriptTarget::identifier)
                    .thenComparing(ScriptTarget::projectName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ScriptTarget::projectName)
                    .thenComparing(ScriptTarget::importSource)
                    .thenComparing(ScriptTarget::includeLabel, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ScriptTarget::includeLabel)
                    .thenComparing(ScriptTarget::filePath, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ScriptTarget::filePath);

    private PapyrusScriptNavigatorModel() {
    }

    static boolean hasScripts(@NotNull ProjectInfos infos) {
        for (ProjectInfo project : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : project.getSourceIncludes()) {
                if (!include.getScripts().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    static @NotNull List<ScriptTarget> targets(@NotNull ProjectInfos infos) {
        List<ScriptTarget> result = new ArrayList<>();
        for (ProjectInfo project : infos.getProjects()) {
            String projectName = nonBlank(project.getName(), "Project");
            for (ProjectInfoSourceInclude include : project.getSourceIncludes()) {
                String includeLabel = PapyrusProjectsPresentation.formatIncludeLabel(include);
                for (ProjectInfoScript script : include.getScripts()) {
                    String filePath = script.getFilePath();
                    if (filePath == null || filePath.isBlank()) {
                        continue;
                    }
                    result.add(new ScriptTarget(
                            nonBlank(script.getIdentifier(), "Script"),
                            projectName,
                            includeLabel,
                            include.isImport(),
                            include.isRemote(),
                            filePath
                    ));
                }
            }
        }
        result.sort(TARGET_ORDER);
        return List.copyOf(result);
    }

    static @NotNull List<ScriptTarget> search(
            @NotNull List<ScriptTarget> targets,
            String query,
            int limit
    ) {
        if (limit <= 0 || targets.isEmpty()) {
            return List.of();
        }

        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return targets.stream().sorted(TARGET_ORDER).limit(limit).toList();
        }

        return targets.stream()
                .map(target -> new RankedTarget(target, rank(target, normalized)))
                .filter(ranked -> ranked.rank() < Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt(RankedTarget::rank)
                        .thenComparing(RankedTarget::target, TARGET_ORDER))
                .limit(limit)
                .map(RankedTarget::target)
                .toList();
    }

    private static int rank(@NotNull ScriptTarget target, @NotNull String query) {
        String identifier = normalize(target.identifier());
        if (identifier.equals(query)) return 0;
        if (identifier.startsWith(query)) return 1;
        if (identifier.contains(query)) return 2;

        String provenance = normalize(target.projectName() + " " + target.includeLabel());
        if (provenance.startsWith(query)) return 3;
        if (provenance.contains(query)) return 4;
        if (normalize(target.filePath()).contains(query)) return 5;
        return Integer.MAX_VALUE;
    }

    private static @NotNull String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static @NotNull String nonBlank(String value, @NotNull String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    record ScriptTarget(
            @NotNull String identifier,
            @NotNull String projectName,
            @NotNull String includeLabel,
            boolean importSource,
            boolean remote,
            @NotNull String filePath
    ) {
        @Override
        public @NotNull String toString() {
            String sourceKind = importSource ? "import" : "source";
            return identifier + " - " + projectName + " / " + includeLabel + " [" + sourceKind + "]";
        }
    }

    private record RankedTarget(@NotNull ScriptTarget target, int rank) {
    }
}
