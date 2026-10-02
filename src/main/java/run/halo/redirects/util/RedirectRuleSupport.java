package run.halo.redirects.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import run.halo.redirects.config.RedirectSettings;

public final class RedirectRuleSupport {
    public static final String MATCH_TYPE_EXACT = "EXACT";
    public static final String MATCH_TYPE_DIRECTORY = "DIRECTORY";
    public static final int DEFAULT_STATUS_CODE = 301;
    public static final int STATUS_GONE = 410;

    private static final Set<Integer> SUPPORTED_STATUS_CODES = Set.of(301, 302, 307, 308, 410);

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

    /**
     * Returns the status code if it is supported, otherwise the default 301.
     */
    public static int normalizeStatusCode(Integer statusCode) {
        return statusCode != null && SUPPORTED_STATUS_CODES.contains(statusCode)
            ? statusCode : DEFAULT_STATUS_CODE;
    }

    /**
     * Parses a status code typed by the user; blank or unsupported values fall back to 301.
     */
    public static int parseStatusCode(String rawStatusCode) {
        return normalizeStatusCode(toStatusCode(rawStatusCode));
    }

    /**
     * Returns the status code when the text is exactly a supported code, otherwise null.
     */
    public static Integer toStatusCode(String rawStatusCode) {
        if (!hasText(rawStatusCode)) {
            return null;
        }
        try {
            var statusCode = Integer.parseInt(rawStatusCode.trim());
            return SUPPORTED_STATUS_CODES.contains(statusCode) ? statusCode : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static boolean isGone(Integer statusCode) {
        return statusCode != null && statusCode == STATUS_GONE;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
