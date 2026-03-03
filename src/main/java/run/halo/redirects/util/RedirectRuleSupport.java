package run.halo.redirects.util;

import java.util.ArrayList;
import java.util.List;
import run.halo.redirects.config.RedirectSettings;

public final class RedirectRuleSupport {
    public static final String MATCH_TYPE_EXACT = "EXACT";
    public static final String MATCH_TYPE_DIRECTORY = "DIRECTORY";

    private RedirectRuleSupport() {
    }

    public static List<RedirectSettings.RedirectRule> collectRules(RedirectSettings settings) {
        var mergedRules = new ArrayList<RedirectSettings.RedirectRule>();
        if (settings == null) {
            return mergedRules;
        }

        mergedRules.addAll(BulkRedirectRuleParser.parse(settings.getBulkRules()));

        if (settings.getRules() != null) {
            mergedRules.addAll(settings.getRules());
        }

        return mergedRules;
    }

    public static String normalizeMatchType(String rawMatchType) {
        if (!hasText(rawMatchType)) {
            return MATCH_TYPE_EXACT;
        }

        var normalized = rawMatchType.trim().toUpperCase();
        if ("DIR".equals(normalized)
            || "DIRECTORY".equals(normalized)
            || "FOLDER".equals(normalized)
            || "PREFIX".equals(normalized)
            || "PATH_PREFIX".equals(normalized)) {
            return MATCH_TYPE_DIRECTORY;
        }

        return MATCH_TYPE_EXACT;
    }

    public static boolean isDirectoryMatch(RedirectSettings.RedirectRule rule) {
        return rule != null && MATCH_TYPE_DIRECTORY.equals(normalizeMatchType(rule.getMatchType()));
    }

    public static boolean isKnownMatchType(String rawMatchType) {
        if (!hasText(rawMatchType)) {
            return false;
        }

        var normalized = rawMatchType.trim().toUpperCase();
        return "EXACT".equals(normalized)
            || "DIR".equals(normalized)
            || "DIRECTORY".equals(normalized)
            || "FOLDER".equals(normalized)
            || "PREFIX".equals(normalized)
            || "PATH_PREFIX".equals(normalized);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
