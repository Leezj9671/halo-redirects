package run.halo.redirects.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import run.halo.redirects.config.RedirectSettings;

public final class RedirectRuleFileCodec {
    private static final List<String> HEADERS =
        List.of("fromPath", "toPath", "statusCode", "note", "matchType");

    private RedirectRuleFileCodec() {
    }

    public static List<RedirectSettings.RedirectRule> importRules(String filename, byte[] content) {
        var format = detectFormat(filename);
        if (format == FileFormat.XLSX) {
            return parseXlsx(content);
        }

        return parseCsv(new String(content, StandardCharsets.UTF_8));
    }

    public static byte[] exportRules(List<RedirectSettings.RedirectRule> rules, String rawFormat) {
        var format = parseFormat(rawFormat);
        if (format == FileFormat.XLSX) {
            return writeXlsx(rules);
        }

        return writeCsv(rules).getBytes(StandardCharsets.UTF_8);
    }

    public static String normalizeExportFormat(String rawFormat) {
        return parseFormat(rawFormat).name().toLowerCase(Locale.ROOT);
    }

    private static List<RedirectSettings.RedirectRule> parseCsv(String rawContent) {
        var rows = parseCsvRows(stripBom(rawContent));
        return toRules(rows);
    }

    private static List<RedirectSettings.RedirectRule> parseXlsx(byte[] content) {
        try (var inputStream = new ByteArrayInputStream(content);
             var workbook = WorkbookFactory.create(inputStream)) {
            var rows = new ArrayList<List<String>>();
            var formatter = new DataFormatter();
            var sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return List.of();
            }

            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }

                var values = new ArrayList<String>(HEADERS.size());
                for (var index = 0; index < HEADERS.size(); index++) {
                    var cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(values);
            }

            return toRules(rows);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to read xlsx file", ex);
        }
    }

    private static String writeCsv(List<RedirectSettings.RedirectRule> rules) {
        var lines = new ArrayList<String>();
        lines.add(String.join(",", HEADERS));

        for (var rule : rules) {
            lines.add(String.join(",",
                csvCell(rule.getFromPath()),
                csvCell(rule.getToPath()),
                csvCell(String.valueOf(normalizeStatusCode(rule.getStatusCode()))),
                csvCell(nullToEmpty(rule.getNote())),
                csvCell(RedirectRuleSupport.normalizeMatchType(rule.getMatchType()))
            ));
        }

        return String.join(System.lineSeparator(), lines);
    }

    private static byte[] writeXlsx(List<RedirectSettings.RedirectRule> rules) {
        try (var workbook = new XSSFWorkbook();
             var outputStream = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("redirects");

            var headerRow = sheet.createRow(0);
            for (var index = 0; index < HEADERS.size(); index++) {
                headerRow.createCell(index).setCellValue(HEADERS.get(index));
            }

            for (var rowIndex = 0; rowIndex < rules.size(); rowIndex++) {
                var rule = rules.get(rowIndex);
                var row = sheet.createRow(rowIndex + 1);
                row.createCell(0).setCellValue(nullToEmpty(rule.getFromPath()));
                row.createCell(1).setCellValue(nullToEmpty(rule.getToPath()));
                row.createCell(2).setCellValue(String.valueOf(normalizeStatusCode(rule.getStatusCode())));
                row.createCell(3).setCellValue(nullToEmpty(rule.getNote()));
                row.createCell(4).setCellValue(RedirectRuleSupport.normalizeMatchType(rule.getMatchType()));
            }

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to create xlsx file", ex);
        }
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

        if (!hasText(fromPath) && !hasText(toPath)) {
            return null;
        }

        if (!hasText(fromPath) || !hasText(toPath)) {
            return null;
        }

        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(fromPath.trim());
        rule.setToPath(toPath.trim());
        rule.setStatusCode(parseStatusCode(readValue(row, headerMap, "statusCode", 2)));

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

    private static Integer parseStatusCode(String rawStatusCode) {
        if (!hasText(rawStatusCode)) {
            return 301;
        }

        return "302".equals(rawStatusCode.trim()) ? 302 : 301;
    }

    private static int normalizeStatusCode(Integer statusCode) {
        return statusCode != null && statusCode == 302 ? 302 : 301;
    }

    private static FileFormat detectFormat(String filename) {
        if (!hasText(filename)) {
            throw new IllegalArgumentException("File name is required");
        }

        var normalized = filename.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".xlsx")) {
            return FileFormat.XLSX;
        }

        if (normalized.endsWith(".csv")) {
            return FileFormat.CSV;
        }

        throw new IllegalArgumentException("Only .csv and .xlsx files are supported");
    }

    private static FileFormat parseFormat(String rawFormat) {
        if (!hasText(rawFormat)) {
            return FileFormat.CSV;
        }

        return switch (rawFormat.trim().toLowerCase(Locale.ROOT)) {
            case "csv" -> FileFormat.CSV;
            case "xlsx" -> FileFormat.XLSX;
            default -> throw new IllegalArgumentException("Unsupported export format: " + rawFormat);
        };
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

    private enum FileFormat {
        CSV,
        XLSX
    }
}
