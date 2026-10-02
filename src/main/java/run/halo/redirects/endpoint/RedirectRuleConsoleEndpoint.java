package run.halo.redirects.endpoint;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.config.RedirectSettingsLoader;
import run.halo.redirects.extension.RedirectRule;
import run.halo.redirects.manager.RedirectRuleRegistry;
import run.halo.redirects.service.RedirectRuleReloader;
import run.halo.redirects.service.RedirectRuleService;
import run.halo.redirects.util.BulkRedirectRuleParser;
import run.halo.redirects.util.PathNormalizer;
import run.halo.redirects.util.RedirectRuleFileCodec;

@Component
@RestController
@RequestMapping("/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects")
public class RedirectRuleConsoleEndpoint {
    /**
     * Upper bound for following a chain in the URL tester; matches the browser limit.
     */
    private static final int MAX_TEST_HOPS = 20;

    private final RedirectSettingsLoader settingsLoader;
    private final RedirectRuleService ruleService;
    private final RedirectRuleReloader reloader;

    public RedirectRuleConsoleEndpoint(RedirectSettingsLoader settingsLoader,
        RedirectRuleService ruleService, RedirectRuleReloader reloader) {
        this.settingsLoader = settingsLoader;
        this.ruleService = ruleService;
        this.reloader = reloader;
    }

    @GetMapping("/settings")
    public Mono<SettingsPayload> getSettings() {
        return Mono.zip(settingsLoader.load(), ruleService.listDefinitions())
            .map(tuple -> new SettingsPayload(Boolean.TRUE.equals(tuple.getT1().getEnabled()),
                Boolean.TRUE.equals(tuple.getT1().getPreserveQueryString()), tuple.getT2()));
    }

    /**
     * Updates the switches; when {@code rules} is present it replaces every rule as well.
     */
    @PutMapping(value = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SettingsPayload> updateSettings(@RequestBody SettingsPayload payload) {
        var replaceRules = payload.rules() == null ? Mono.empty()
            : ruleService.replaceAll(payload.rules());
        return settingsLoader.load()
            .flatMap(settings -> {
                settings.setEnabled(payload.enabled());
                settings.setPreserveQueryString(payload.preserveQueryString());
                return settingsLoader.save(settings);
            })
            .onErrorMap(IllegalStateException.class, ex -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, ex.getMessage(), ex))
            .then(replaceRules)
            .then(reload())
            .then(getSettings());
    }

    @GetMapping("/rules")
    public Mono<RuleList> listRules() {
        return Mono.zip(settingsLoader.load(), ruleService.listAll().collectList())
            .map(tuple -> {
                var skipped = new HashSet<String>();
                RedirectRuleRegistry.skippedRules().forEach(rule -> skipped.add(rule.name()));
                var items = tuple.getT2().stream()
                    .map(rule -> RuleView.of(rule, skipped.contains(rule.getMetadata().getName())))
                    .toList();
                return new RuleList(Boolean.TRUE.equals(tuple.getT1().getEnabled()),
                    Boolean.TRUE.equals(tuple.getT1().getPreserveQueryString()), items);
            });
    }

    @PostMapping(value = "/rules", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RuleView> createRule(@RequestBody RedirectSettings.RedirectRule input) {
        return ruleService.create(input).flatMap(this::viewAfterReload);
    }

    @PutMapping(value = "/rules/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RuleView> updateRule(@PathVariable("name") String name,
        @RequestBody RedirectSettings.RedirectRule input) {
        return ruleService.update(name, input).flatMap(this::viewAfterReload);
    }

    @DeleteMapping("/rules/{name}")
    public Mono<ResponseEntity<Void>> deleteRule(@PathVariable("name") String name) {
        return ruleService.delete(name).then(reload())
            .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping(value = "/rules/-/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<DeleteResult> deleteRules(@RequestBody NameList body) {
        return ruleService.deleteAll(body.names())
            .flatMap(deleted -> reload().thenReturn(new DeleteResult(deleted)));
    }

    /**
     * Adds the rules pasted as text, one per line (same format as the old "bulk" box). A rule
     * with the same source path and match type as an existing one updates it.
     */
    @PostMapping(value = "/rules/-/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ImportResult> bulkAdd(@RequestBody BulkRequest body) {
        var rules = BulkRedirectRuleParser.parse(body.text());
        if (rules.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "没有识别到有效规则，请检查格式"));
        }
        return ruleService.upsertAll(rules)
            .flatMap(result -> reload().then(totalRules())
                .map(total -> ImportResult.of(result, total, "append")));
    }

    /**
     * Shows what a visitor would get for a URL or path, following local redirects.
     */
    @GetMapping("/rules/-/test")
    public Mono<TestResult> testUrl(@RequestParam("url") String rawUrl) {
        var parsed = parseTestUrl(rawUrl);
        return settingsLoader.load().map(settings -> {
            var pluginEnabled = Boolean.TRUE.equals(settings.getEnabled());
            var hops = new ArrayList<TestHop>();
            var path = parsed.path();
            var query = parsed.rawQuery();
            var visited = new HashSet<String>();
            while (pluginEnabled && hops.size() < MAX_TEST_HOPS) {
                var explanation = RedirectRuleRegistry.explain(path, query);
                if (explanation.isEmpty()) {
                    break;
                }
                var hop = explanation.get();
                hops.add(new TestHop(path, hop.ruleName(), hop.sourcePath(), hop.directory(),
                    hop.statusCode(), hop.location()));
                if (hop.location() == null || !PathNormalizer.isLocalPath(hop.location())
                    || !visited.add(path)) {
                    break;
                }
                var next = URI.create(hop.location());
                path = next.getPath();
                query = next.getRawQuery();
            }
            return new TestResult(rawUrl, parsed.path(), pluginEnabled, !hops.isEmpty(), hops);
        });
    }

    @PostMapping(value = "/rules/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ImportResult> importRules(@RequestPart("file") FilePart filePart,
        @RequestParam(name = "mode", defaultValue = "replace") String rawMode) {
        var mode = ImportMode.from(rawMode);

        return readContent(filePart)
            .flatMap(content -> Mono.fromCallable(
                    () -> RedirectRuleFileCodec.importRules(filePart.filename(), content))
                .onErrorMap(IllegalArgumentException.class, ex -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, ex.getMessage(), ex)))
            .flatMap(importedRules -> {
                if (importedRules.isEmpty()) {
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The uploaded file does not contain any valid redirect rules"));
                }
                var write = mode == ImportMode.REPLACE
                    ? ruleService.replaceAll(importedRules)
                    : ruleService.upsertAll(importedRules);
                return write.flatMap(result -> reload().then(totalRules())
                    .map(total -> ImportResult.of(result, total, mode.value())));
            });
    }

    @GetMapping("/rules/export")
    public Mono<ResponseEntity<byte[]>> exportRules(
        @RequestParam(name = "format", defaultValue = "csv") String format) {
        return Mono.fromCallable(() -> RedirectRuleFileCodec.normalizeExportFormat(format))
            .onErrorMap(IllegalArgumentException.class, ex -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, ex.getMessage(), ex))
            .flatMap(normalizedFormat -> ruleService.listDefinitions().map(rules ->
                ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"redirect-rules." + normalizedFormat + "\"")
                    .body(RedirectRuleFileCodec.exportRules(rules, normalizedFormat))));
    }

    private Mono<Void> reload() {
        return reloader.reloadNow("console api");
    }

    private Mono<Integer> totalRules() {
        return ruleService.listAll().count().map(Long::intValue);
    }

    private Mono<RuleView> viewAfterReload(RedirectRule rule) {
        return reload().then(Mono.fromSupplier(() -> RuleView.of(rule,
            RedirectRuleRegistry.skippedRules().stream()
                .anyMatch(skipped -> rule.getMetadata().getName().equals(skipped.name())))));
    }

    static ParsedUrl parseTestUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入要测试的地址");
        }
        var value = rawUrl.trim();
        try {
            // Allow typing a raw (unencoded) path such as /archives/旧文章.
            var uri = URI.create(PathNormalizer.toHeaderValue(
                PathNormalizer.isAbsoluteUrl(value) || value.startsWith("/") ? value : "/" + value));
            var path = uri.getPath();
            return new ParsedUrl(path == null || path.isEmpty() ? "/" : path, uri.getRawQuery());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法解析的地址：" + rawUrl);
        }
    }

    private Mono<byte[]> readContent(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
            .map(dataBuffer -> {
                var bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                return bytes;
            });
    }

    record ParsedUrl(String path, String rawQuery) {
    }

    public record ImportResult(int importedCount, int createdCount, int updatedCount,
                               int skippedCount, int totalRuleCount, String mode) {
        static ImportResult of(RedirectRuleService.WriteResult result, int total, String mode) {
            return new ImportResult(result.created() + result.updated(), result.created(),
                result.updated(), result.skipped(), total, mode);
        }
    }

    public record SettingsPayload(boolean enabled, boolean preserveQueryString,
                                  List<RedirectSettings.RedirectRule> rules) {
    }

    public record RuleView(String name, Long version, Instant creationTimestamp, String fromPath,
                           String toPath, String matchType, int statusCode, String note,
                           boolean enabled, boolean skippedForLoop) {
        static RuleView of(RedirectRule rule, boolean skippedForLoop) {
            var definition = RedirectRuleService.toDefinition(rule);
            return new RuleView(definition.getName(), rule.getMetadata().getVersion(),
                rule.getMetadata().getCreationTimestamp(), definition.getFromPath(),
                definition.getToPath(), definition.getMatchType(), definition.getStatusCode(),
                definition.getNote(), definition.getEnabled(), skippedForLoop);
        }
    }

    public record RuleList(boolean pluginEnabled, boolean preserveQueryString,
                           List<RuleView> items) {
    }

    public record NameList(List<String> names) {
    }

    public record DeleteResult(int deletedCount) {
    }

    public record BulkRequest(String text) {
    }

    public record TestHop(String path, String ruleName, String fromPath, boolean directory,
                          int statusCode, String location) {
    }

    public record TestResult(String input, String path, boolean pluginEnabled, boolean matched,
                             List<TestHop> hops) {
    }

    private enum ImportMode {
        REPLACE("replace"),
        APPEND("append");

        private final String value;

        ImportMode(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }

        private static ImportMode from(String rawMode) {
            if (rawMode == null || rawMode.isBlank() || "replace".equalsIgnoreCase(rawMode)) {
                return REPLACE;
            }

            if ("append".equalsIgnoreCase(rawMode)) {
                return APPEND;
            }

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "mode must be replace or append");
        }
    }
}
