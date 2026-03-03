package run.halo.redirects.manager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.util.BulkRedirectRuleParser;
import run.halo.redirects.util.PathNormalizer;

public final class RedirectRuleRegistry {
    private static final String SETTINGS_UPDATE_PATH =
        "/apis/api.console.halo.run/v1alpha1/plugins/redirects/config";
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

        var reloadedRules = new LinkedHashMap<String, StoredRule>();

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

            reloadedRules.put(
                sourcePath,
                new StoredRule(target, normalizeStatusCode(rule.getStatusCode()), rule.getNote())
            );
        }

        if (reloadedRules.isEmpty()) {
            clear();
            return;
        }

        SNAPSHOT.set(new Snapshot(Map.copyOf(reloadedRules),
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
        var rule = snapshot.rules().get(normalizedPath);
        if (rule == null) {
            return Optional.empty();
        }

        var location = snapshot.preserveQueryString()
            ? PathNormalizer.appendRawQuery(rule.target(), rawQuery)
            : rule.target();

        return Optional.of(new ResolvedRedirect(location, rule.statusCode()));
    }

    public static boolean isEnabled() {
        return SNAPSHOT.get().enabled();
    }

    public static int size() {
        return SNAPSHOT.get().rules().size();
    }

    public static boolean isSettingsMutation(ServerHttpRequest request) {
        var method = request.getMethod();
        return SETTINGS_UPDATE_PATH.equals(request.getURI().getPath())
            && method != null
            && method != HttpMethod.GET;
    }

    private static int normalizeStatusCode(Integer statusCode) {
        return statusCode != null && statusCode == 302 ? 302 : 301;
    }

    private static List<RedirectSettings.RedirectRule> collectRules(RedirectSettings settings) {
        var mergedRules = new ArrayList<RedirectSettings.RedirectRule>();
        mergedRules.addAll(BulkRedirectRuleParser.parse(settings.getBulkRules()));

        if (settings.getRules() != null) {
            mergedRules.addAll(settings.getRules());
        }

        return mergedRules;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record Snapshot(Map<String, StoredRule> rules, boolean preserveQueryString) {
        private static Snapshot disabled() {
            return new Snapshot(Map.of(), false);
        }

        private boolean enabled() {
            return !rules.isEmpty();
        }
    }

    private record StoredRule(String target, int statusCode, String note) {
    }

    public record ResolvedRedirect(String location, int statusCode) {
    }
}
