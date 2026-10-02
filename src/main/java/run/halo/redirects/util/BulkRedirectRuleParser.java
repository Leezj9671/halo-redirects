package run.halo.redirects.util;

import java.util.ArrayList;
import java.util.List;
import run.halo.redirects.config.RedirectSettings;

public final class BulkRedirectRuleParser {
    private BulkRedirectRuleParser() {
    }

    public static List<RedirectSettings.RedirectRule> parse(String bulkRules) {
        var parsedRules = new ArrayList<RedirectSettings.RedirectRule>();
        if (!hasText(bulkRules)) {
            return parsedRules;
        }

        for (var rawLine : bulkRules.split("\\R")) {
            var parsedRule = parseLine(rawLine);
            if (parsedRule != null) {
                parsedRules.add(parsedRule);
            }
        }

        return parsedRules;
    }

    private static RedirectSettings.RedirectRule parseLine(String rawLine) {
        if (!hasText(rawLine)) {
            return null;
        }

        var line = rawLine.trim();
        if (line.startsWith("#")) {
            return null;
        }

        var parts = splitLine(line);
        if (parts == null || parts.length < 2) {
            return null;
        }

        var fromPath = parts[0].trim();
        var toPath = parts[1].trim();
        var statusCode = RedirectRuleSupport.parseStatusCode(parts.length >= 3 ? parts[2] : null);

        // "/gone -> 410": a bare 410 in the target column means the page is gone.
        if (parts.length == 2 && RedirectRuleSupport.isGone(RedirectRuleSupport.toStatusCode(toPath))) {
            toPath = "";
            statusCode = RedirectRuleSupport.STATUS_GONE;
        }

        if (!hasText(fromPath) || (!hasText(toPath) && !RedirectRuleSupport.isGone(statusCode))) {
            return null;
        }

        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(fromPath);
        rule.setToPath(hasText(toPath) ? toPath : null);
        rule.setStatusCode(statusCode);

        if (parts.length >= 4 && hasText(parts[3])) {
            if (parts.length == 4 && RedirectRuleSupport.isKnownMatchType(parts[3])) {
                rule.setMatchType(RedirectRuleSupport.normalizeMatchType(parts[3]));
            } else {
                rule.setNote(parts[3].trim());
            }
        }

        if (parts.length >= 5 && hasText(parts[4])) {
            rule.setMatchType(RedirectRuleSupport.normalizeMatchType(parts[4]));
        }

        return rule;
    }

    private static String[] splitLine(String line) {
        if (line.contains("=>")) {
            return line.split("\\s*=>\\s*", 5);
        }

        if (line.contains("->")) {
            return line.split("\\s*->\\s*", 5);
        }

        if (line.contains(",")) {
            return line.split("\\s*,\\s*", 5);
        }

        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
