package run.halo.redirects.manager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.util.PathNormalizer;
import run.halo.redirects.util.RedirectRuleSupport;

public final class RedirectRuleRegistry {
    private static final AtomicReference<Snapshot> SNAPSHOT =
        new AtomicReference<>(Snapshot.disabled());

    private RedirectRuleRegistry() {
    }

    public static void reload(RedirectSettings settings) {
        if (settings == null || !Boolean.TRUE.equals(settings.getEnabled())) {
            clear();
            return;
        }

        var sourceRules = collectRules(settings);
        if (sourceRules.isEmpty()) {
            clear();
            return;
        }

        var reloadedExactRules = new LinkedHashMap<String, StoredRule>();
        var reloadedDirectoryRules = new LinkedHashMap<String, DirectoryRule>();

        for (var rule : sourceRules) {
            var sourcePath = PathNormalizer.normalizePath(rule.getFromPath());
            var target = PathNormalizer.normalizeTarget(rule.getToPath());

            if (!hasText(sourcePath) || !hasText(target)) {
                continue;
            }

            if (PathNormalizer.isLocalPath(target)
                && Objects.equals(sourcePath, PathNormalizer.normalizePath(target))) {
                continue;
            }

            if (RedirectRuleSupport.isDirectoryMatch(rule)) {
                reloadedDirectoryRules.put(
                    sourcePath,
                    new DirectoryRule(
                        sourcePath,
                        target,
                        normalizeStatusCode(rule.getStatusCode()),
                        rule.getNote()
                    )
                );
                continue;
            }

            reloadedExactRules.put(
                sourcePath,
                new StoredRule(target, normalizeStatusCode(rule.getStatusCode()), rule.getNote())
            );
        }

        if (reloadedExactRules.isEmpty() && reloadedDirectoryRules.isEmpty()) {
            clear();
            return;
        }

        var sortedDirectoryRules = reloadedDirectoryRules.values().stream()
            .sorted(Comparator.comparingInt((DirectoryRule rule) -> rule.sourcePath().length())
                .reversed())
            .toList();

        SNAPSHOT.set(new Snapshot(Map.copyOf(reloadedExactRules), List.copyOf(sortedDirectoryRules),
            Boolean.TRUE.equals(settings.getPreserveQueryString())));
    }

    public static void clear() {
        SNAPSHOT.set(Snapshot.disabled());
    }

    public static Optional<ResolvedRedirect> resolve(String requestPath, String rawQuery) {
        var normalizedPath = PathNormalizer.normalizePath(requestPath);
        if (!hasText(normalizedPath)) {
            return Optional.empty();
        }

        var snapshot = SNAPSHOT.get();
        var exactRule = snapshot.exactRules().get(normalizedPath);
        if (exactRule != null) {
            return Optional.of(new ResolvedRedirect(
                buildLocation(snapshot.preserveQueryString(), exactRule.target(), null, rawQuery),
                exactRule.statusCode()
            ));
        }

        for (var directoryRule : snapshot.directoryRules()) {
            if (!matchesDirectory(directoryRule.sourcePath(), normalizedPath)) {
                continue;
            }

            return Optional.of(new ResolvedRedirect(
                buildLocation(snapshot.preserveQueryString(), directoryRule.target(),
                    suffixFor(directoryRule.sourcePath(), normalizedPath), rawQuery),
                directoryRule.statusCode()
            ));
        }

        return Optional.empty();
    }

    public static boolean isEnabled() {
        return SNAPSHOT.get().enabled();
    }

    public static int size() {
        var snapshot = SNAPSHOT.get();
        return snapshot.exactRules().size() + snapshot.directoryRules().size();
    }

    private static int normalizeStatusCode(Integer statusCode) {
        return statusCode != null && statusCode == 302 ? 302 : 301;
    }

    private static List<RedirectSettings.RedirectRule> collectRules(RedirectSettings settings) {
        return new ArrayList<>(RedirectRuleSupport.collectRules(settings));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean matchesDirectory(String sourcePath, String requestPath) {
        if ("/".equals(sourcePath)) {
            return requestPath.startsWith("/");
        }

        return requestPath.equals(sourcePath) || requestPath.startsWith(sourcePath + "/");
    }

    private static String suffixFor(String sourcePath, String requestPath) {
        if ("/".equals(sourcePath)) {
            return "/".equals(requestPath) ? "" : requestPath;
        }

        return requestPath.equals(sourcePath) ? "" : requestPath.substring(sourcePath.length());
    }

    private static String buildLocation(boolean preserveQueryString, String target, String suffix,
        String rawQuery) {
        var location = applySuffix(target, suffix);
        return preserveQueryString ? PathNormalizer.appendRawQuery(location, rawQuery) : location;
    }

    private static String applySuffix(String target, String suffix) {
        if (!hasText(target) || !hasText(suffix)) {
            return target;
        }

        var anchorIndex = target.indexOf('#');
        var beforeAnchor = anchorIndex >= 0 ? target.substring(0, anchorIndex) : target;
        var anchor = anchorIndex >= 0 ? target.substring(anchorIndex) : "";

        var queryIndex = beforeAnchor.indexOf('?');
        var base = queryIndex >= 0 ? beforeAnchor.substring(0, queryIndex) : beforeAnchor;
        var query = queryIndex >= 0 ? beforeAnchor.substring(queryIndex) : "";

        if (base.endsWith("/") && suffix.startsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        return base + suffix + query + anchor;
    }

    private record Snapshot(Map<String, StoredRule> exactRules, List<DirectoryRule> directoryRules,
                            boolean preserveQueryString) {
        private static Snapshot disabled() {
            return new Snapshot(Map.of(), List.of(), false);
        }

        private boolean enabled() {
            return !exactRules.isEmpty() || !directoryRules.isEmpty();
        }
    }

    private record StoredRule(String target, int statusCode, String note) {
    }

    private record DirectoryRule(String sourcePath, String target, int statusCode, String note) {
    }

    public record ResolvedRedirect(String location, int statusCode) {
    }
}
