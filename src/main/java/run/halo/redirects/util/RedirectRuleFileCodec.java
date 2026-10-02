package run.halo.redirects.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import run.halo.redirects.config.RedirectSettings;

/**
 * Imports and exports redirect rules as CSV (UTF-8, optional BOM). Spreadsheet apps such as Excel
 * and Numbers open and save this format directly, so no spreadsheet library is bundled.
 */
public final class RedirectRuleFileCodec {
    private static final List<String> HEADERS =
        List.of("fromPath", "toPath", "statusCode", "note", "matchType");

    private RedirectRuleFileCodec() {
    }

    public static List<RedirectSettings.RedirectRule> importRules(String filename, byte[] content) {
        checkCsvFilename(filename);
        return parseCsv(new String(content, StandardCharsets.UTF_8));
    }

    public static byte[] exportRules(List<RedirectSettings.RedirectRule> rules, String rawFormat) {
        normalizeExportFormat(rawFormat);
        // The BOM makes Excel read the file as UTF-8 instead of the system code page.
        return ("\ufeff" + writeCsv(rules)).getBytes(StandardCharsets.UTF_8);
    }

    public static String normalizeExportFormat(String rawFormat) {
        if (!hasText(rawFormat) || "csv".equalsIgnoreCase(rawFormat.trim())) {
            return "csv";
        }
        throw new IllegalArgumentException("Unsupported export format: " + rawFormat
            + ". Only csv is supported; spreadsheet apps can open and save csv files.");
    }

    private static List<RedirectSettings.RedirectRule> parseCsv(String rawContent) {
        var rows = parseCsvRows(stripBom(rawContent));
        return toRules(rows);
    }

    private static String writeCsv(List<RedirectSettings.RedirectRule> rules) {
        var lines = new ArrayList<String>();
        lines.add(String.join(",", HEADERS));

        for (var rule : rules) {
            lines.add(String.join(",",
                csvCell(rule.getFromPath()),
                csvCell(rule.getToPath()),
                csvCell(String.valueOf(RedirectRuleSupport.normalizeStatusCode(rule.getStatusCode()))),
                csvCell(nullToEmpty(rule.getNote())),
                csvCell(RedirectRuleSupport.normalizeMatchType(rule.getMatchType()))
            ));
        }

        return String.join(System.lineSeparator(), lines);
    }

    private static List<RedirectSettings.RedirectRule> toRules(List<List<String>> rows) {
        var rules = new ArrayList<RedirectSettings.RedirectRule>();
        if (rows.isEmpty()) {
            return rules;
        }

        var headerMap = parseHeader(rows.get(0));
        var startIndex = headerMap.isEmpty() ? 0 : 1;

        for (var index = startIndex; index < rows.size(); index++) {
            var rule = toRule(rows.get(index), headerMap);
            if (rule != null) {
                rules.add(rule);
            }
        }

        return rules;
    }

    private static Map<String, Integer> parseHeader(List<String> row) {
        var headerMap = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < row.size(); index++) {
            var header = canonicalHeader(row.get(index));
            if (header != null) {
                headerMap.putIfAbsent(header, index);
            }
        }

        if (!headerMap.containsKey("fromPath") || !headerMap.containsKey("toPath")) {
            return Map.of();
        }

        return headerMap;
    }

    private static RedirectSettings.RedirectRule toRule(List<String> row, Map<String, Integer> headerMap) {
        var fromPath = readValue(row, headerMap, "fromPath", 0);
        var toPath = readValue(row, headerMap, "toPath", 1);

        var statusCode = RedirectRuleSupport.parseStatusCode(
            readValue(row, headerMap, "statusCode", 2));
        if (!hasText(fromPath) || (!hasText(toPath) && !RedirectRuleSupport.isGone(statusCode))) {
            return null;
        }

        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(fromPath.trim());
        rule.setToPath(hasText(toPath) ? toPath.trim() : null);
        rule.setStatusCode(statusCode);

        var note = readValue(row, headerMap, "note", 3);
        if (hasText(note)) {
            rule.setNote(note.trim());
        }

        var matchType = readValue(row, headerMap, "matchType", 4);
        if (hasText(matchType)) {
            rule.setMatchType(RedirectRuleSupport.normalizeMatchType(matchType));
        }

        return rule;
    }

    private static String readValue(List<String> row, Map<String, Integer> headerMap, String key,
        int fallbackIndex) {
        var index = headerMap.getOrDefault(key, fallbackIndex);
        if (index < 0 || index >= row.size()) {
            return null;
        }

        return row.get(index);
    }

    private static String canonicalHeader(String rawHeader) {
        if (!hasText(rawHeader)) {
            return null;
        }

        var normalized = rawHeader.trim()
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
            .toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "from", "frompath", "source", "sourcepath" -> "fromPath";
            case "to", "topath", "target", "targetpath" -> "toPath";
            case "status", "statuscode", "code" -> "statusCode";
            case "note", "remark", "description" -> "note";
            case "match", "matchtype", "ruletype", "type" -> "matchType";
            default -> null;
        };
    }

    private static List<List<String>> parseCsvRows(String rawContent) {
        var rows = new ArrayList<List<String>>();
        var currentRow = new ArrayList<String>();
        var currentCell = new StringBuilder();
        var quoted = false;

        for (var index = 0; index < rawContent.length(); index++) {
            var ch = rawContent.charAt(index);

            if (ch == '"') {
                if (quoted && index + 1 < rawContent.length() && rawContent.charAt(index + 1) == '"') {
                    currentCell.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }

            if (!quoted && ch == ',') {
                currentRow.add(currentCell.toString());
                currentCell.setLength(0);
                continue;
            }

            if (!quoted && (ch == '\n' || ch == '\r')) {
                currentRow.add(currentCell.toString());
                currentCell.setLength(0);
                rows.add(currentRow);
                currentRow = new ArrayList<>();

                if (ch == '\r' && index + 1 < rawContent.length() && rawContent.charAt(index + 1) == '\n') {
                    index++;
                }
                continue;
            }

            currentCell.append(ch);
        }

        currentRow.add(currentCell.toString());
        if (!currentRow.isEmpty() && !(currentRow.size() == 1 && currentRow.get(0).isEmpty())) {
            rows.add(currentRow);
        }

        return rows;
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }

        var escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\r")
            || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }

        return escaped;
    }

    private static void checkCsvFilename(String filename) {
        if (!hasText(filename)) {
            throw new IllegalArgumentException("File name is required");
        }
        if (!filename.trim().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException(
                "Only .csv files are supported; save the spreadsheet as CSV (UTF-8) first");
        }
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\ufeff') {
            return value.substring(1);
        }

        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
