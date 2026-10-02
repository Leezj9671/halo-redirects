package run.halo.redirects.manager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.util.PathNormalizer;
import run.halo.redirects.util.RedirectRuleSupport;

public final class RedirectRuleRegistry {
    private static final Logger log = LoggerFactory.getLogger(RedirectRuleRegistry.class);

    /**
     * Browsers give up after about 20 redirects, so a longer local chain is as broken as a loop.
     */
    private static final int MAX_HOPS = 20;
    private static final String LOOP_PROBE_SEGMENT = "/redirects-loop-probe";

    private static final AtomicReference<Snapshot> SNAPSHOT =
        new AtomicReference<>(Snapshot.disabled());

    private RedirectRuleRegistry() {
    }

    public static void reload(RedirectSettings settings) {
        if (settings == null || !Boolean.TRUE.equals(settings.getEnabled())) {
            clear();
            return;
        }

        var exactRules = new LinkedHashMap<String, Rule>();
        var directoryRules = new LinkedHashMap<String, Rule>();

        for (var sourceRule : RedirectRuleSupport.collectRules(settings)) {
            if (Boolean.FALSE.equals(sourceRule.getEnabled())) {
                continue;
            }
            var rule = compile(sourceRule);
            if (rule == null) {
                continue;
            }
            (rule.directory() ? directoryRules : exactRules).put(rule.sourcePath(), rule);
        }

        var snapshot = Snapshot.of(exactRules.values(), directoryRules.values(),
            Boolean.TRUE.equals(settings.getPreserveQueryString()));

        var loopingRules = findLoopingRules(snapshot);
        if (!loopingRules.isEmpty()) {
            log.warn("[redirects] skipped {} rule(s) that form a redirect loop or a chain longer "
                + "than {} hops: {}", loopingRules.size(), MAX_HOPS, loopingRules.stream()
                .map(rule -> rule.sourcePath() + " -> " + rule.target())
                .collect(Collectors.joining(", ")));
            snapshot = snapshot.without(loopingRules).withSkipped(loopingRules.stream()
                .map(rule -> new SkippedRule(rule.name(), rule.sourcePath(), rule.target()))
                .toList());
        }

        SNAPSHOT.set(snapshot);
    }

    public static void clear() {
        SNAPSHOT.set(Snapshot.disabled());
    }

    /**
     * Resolves a decoded request path. The returned location is safe to put into the
     * {@code Location} header as is; it is null for a {@code 410 Gone} rule.
     */
    public static Optional<ResolvedRedirect> resolve(String requestPath, String rawQuery) {
        var normalizedPath = PathNormalizer.normalizePath(requestPath);
        if (!hasText(normalizedPath)) {
            return Optional.empty();
        }

        var snapshot = SNAPSHOT.get();
        return match(snapshot, normalizedPath).map(match -> {
            if (match.rule().isGone()) {
                return new ResolvedRedirect(null, match.rule().statusCode());
            }
            var location = match.location();
            if (snapshot.preserveQueryString()) {
                location = PathNormalizer.appendRawQuery(location, rawQuery);
            }
            return new ResolvedRedirect(PathNormalizer.toHeaderValue(location),
                match.rule().statusCode());
        });
    }

    /**
     * Like {@link #resolve} but also tells which rule matched, for the "test URL" tool.
     */
    public static Optional<Explanation> explain(String requestPath, String rawQuery) {
        var normalizedPath = PathNormalizer.normalizePath(requestPath);
        if (!hasText(normalizedPath)) {
            return Optional.empty();
        }
        var snapshot = SNAPSHOT.get();
        return match(snapshot, normalizedPath).map(match -> {
            var rule = match.rule();
            var resolved = resolve(requestPath, rawQuery).orElseThrow();
            return new Explanation(rule.name(), rule.sourcePath(), rule.directory(),
                resolved.statusCode(), resolved.location());
        });
    }

    /**
     * Rules left out of the last reload because they form a loop or an overlong chain.
     */
    public static List<SkippedRule> skippedRules() {
        return SNAPSHOT.get().skipped();
    }

    public static boolean isEnabled() {
        return SNAPSHOT.get().enabled();
    }

    public static int size() {
        var snapshot = SNAPSHOT.get();
        return snapshot.exactRules().size() + snapshot.directoryRules().size();
    }

    private static Rule compile(RedirectSettings.RedirectRule rule) {
        var sourcePath = PathNormalizer.normalizePath(PathNormalizer.decodePath(rule.getFromPath()));
        if (!hasText(sourcePath)) {
            return null;
        }

        var statusCode = RedirectRuleSupport.normalizeStatusCode(rule.getStatusCode());
        var directory = RedirectRuleSupport.isDirectoryMatch(rule);
        if (RedirectRuleSupport.isGone(statusCode)) {
            return new Rule(rule.getName(), sourcePath, null, statusCode, directory);
        }

        var target = PathNormalizer.normalizeTarget(rule.getToPath());
        if (!hasText(target) || isSelfRedirect(sourcePath, target)) {
            return null;
        }
        return new Rule(rule.getName(), sourcePath, target, statusCode, directory);
    }

    private static boolean isSelfRedirect(String sourcePath, String target) {
        return PathNormalizer.isLocalPath(target)
            && Objects.equals(sourcePath, localPathOf(target));
    }

    private static Optional<Match> match(Snapshot snapshot, String normalizedPath) {
        var exactRule = snapshot.exactRules().get(normalizedPath);
        if (exactRule != null) {
            return Optional.of(new Match(exactRule, exactRule.target()));
        }

        for (var directoryRule : snapshot.directoryRules()) {
            if (matchesDirectory(directoryRule.sourcePath(), normalizedPath)) {
                var suffix = PathNormalizer.encodePath(
                    suffixFor(directoryRule.sourcePath(), normalizedPath));
                return Optional.of(new Match(directoryRule,
                    applySuffix(directoryRule.target(), suffix)));
            }
        }

        return Optional.empty();
    }

    /**
     * Follows every rule through the other local rules and collects the ones that end up in a
     * loop (or in a chain too long for browsers), so they can be left out instead of trapping
     * visitors in {@code ERR_TOO_MANY_REDIRECTS}. Repeats until the remaining rules are clean,
     * because dropping one rule can change where another chain ends.
     */
    private static Set<Rule> findLoopingRules(Snapshot snapshot) {
        var looping = new LinkedHashSet<Rule>();
        var current = snapshot;
        while (true) {
            var found = new LinkedHashSet<Rule>();
            for (var rule : current.allRules()) {
                for (var probe : probesFor(rule)) {
                    found.addAll(followLoop(current, probe));
                }
            }
            if (found.isEmpty()) {
                return looping;
            }
            looping.addAll(found);
            current = current.without(found);
        }
    }

    private static List<String> probesFor(Rule rule) {
        if (!rule.directory()) {
            return List.of(rule.sourcePath());
        }
        var probe = "/".equals(rule.sourcePath())
            ? LOOP_PROBE_SEGMENT : rule.sourcePath() + LOOP_PROBE_SEGMENT;
        return List.of(rule.sourcePath(), probe);
    }

    private static List<Rule> followLoop(Snapshot snapshot, String startPath) {
        var visitedPaths = new ArrayList<String>();
        var appliedRules = new ArrayList<Rule>();
        var path = startPath;

        for (var hop = 0; hop <= MAX_HOPS; hop++) {
            visitedPaths.add(path);
            var match = match(snapshot, path);
            if (match.isEmpty() || match.get().rule().isGone()
                || !PathNormalizer.isLocalPath(match.get().location())) {
                return List.of();
            }

            appliedRules.add(match.get().rule());
            var next = localPathOf(match.get().location());
            var loopStart = visitedPaths.indexOf(next);
            if (loopStart >= 0) {
                return appliedRules.subList(loopStart, appliedRules.size());
            }
            path = next;
        }

        return appliedRules.stream().distinct().toList();
    }

    private static String localPathOf(String location) {
        return PathNormalizer.normalizePath(PathNormalizer.decodePath(stripQueryAndFragment(location)));
    }

    private static String stripQueryAndFragment(String location) {
        var end = location.length();
        var queryIndex = location.indexOf('?');
        var fragmentIndex = location.indexOf('#');
        if (queryIndex >= 0) {
            end = queryIndex;
        }
        if (fragmentIndex >= 0 && fragmentIndex < end) {
            end = fragmentIndex;
        }
        return location.substring(0, end);
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

    private record Snapshot(Map<String, Rule> exactRules, List<Rule> directoryRules,
                            boolean preserveQueryString, List<SkippedRule> skipped) {
        private static Snapshot disabled() {
            return new Snapshot(Map.of(), List.of(), false, List.of());
        }

        private static Snapshot of(Iterable<Rule> exactRules, Iterable<Rule> directoryRules,
            boolean preserveQueryString) {
            var exact = new LinkedHashMap<String, Rule>();
            exactRules.forEach(rule -> exact.put(rule.sourcePath(), rule));
            var directories = new ArrayList<Rule>();
            directoryRules.forEach(directories::add);
            directories.sort(Comparator.comparingInt((Rule rule) -> rule.sourcePath().length())
                .reversed());
            return new Snapshot(Map.copyOf(exact), List.copyOf(directories), preserveQueryString,
                List.of());
        }

        private Snapshot without(Set<Rule> rules) {
            return of(
                exactRules.values().stream().filter(rule -> !rules.contains(rule)).toList(),
                directoryRules.stream().filter(rule -> !rules.contains(rule)).toList(),
                preserveQueryString);
        }

        private Snapshot withSkipped(List<SkippedRule> skippedRules) {
            return new Snapshot(exactRules, directoryRules, preserveQueryString,
                List.copyOf(skippedRules));
        }

        private List<Rule> allRules() {
            var all = new ArrayList<Rule>(exactRules.values());
            all.addAll(directoryRules);
            return all;
        }

        private boolean enabled() {
            return !exactRules.isEmpty() || !directoryRules.isEmpty();
        }
    }

    private record Rule(String name, String sourcePath, String target, int statusCode,
                        boolean directory) {
        private boolean isGone() {
            return RedirectRuleSupport.isGone(statusCode);
        }
    }

    private record Match(Rule rule, String location) {
    }

    /**
     * A rule left out because following it loops or exceeds the hop limit.
     */
    public record SkippedRule(String name, String fromPath, String toPath) {
    }

    /**
     * Which rule answered a path; {@code ruleName} is null for rules without an extension.
     */
    public record Explanation(String ruleName, String sourcePath, boolean directory,
                              int statusCode, String location) {
    }

    /**
     * A resolved rule; {@code location} is null when the rule answers {@code 410 Gone}.
     */
    public record ResolvedRedirect(String location, int statusCode) {
    }
}
