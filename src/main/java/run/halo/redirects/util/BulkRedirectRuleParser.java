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
        if (!hasText(fromPath) || !hasText(toPath)) {
            return null;
        }

        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(fromPath);
        rule.setToPath(toPath);
        rule.setStatusCode(parseStatusCode(parts.length >= 3 ? parts[2] : null));

        if (parts.length >= 4 && hasText(parts[3])) {
            rule.setNote(parts[3].trim());
        }

        return rule;
    }

    private static String[] splitLine(String line) {
        if (line.contains("=>")) {
            return line.split("\\s*=>\\s*", 4);
        }

        if (line.contains("->")) {
            return line.split("\\s*->\\s*", 4);
        }

        if (line.contains(",")) {
            return line.split("\\s*,\\s*", 4);
        }

        return null;
    }

    private static Integer parseStatusCode(String rawStatusCode) {
        if (!hasText(rawStatusCode)) {
            return 301;
        }

        var statusCode = rawStatusCode.trim();
        if ("302".equals(statusCode)) {
            return 302;
        }

        return 301;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
