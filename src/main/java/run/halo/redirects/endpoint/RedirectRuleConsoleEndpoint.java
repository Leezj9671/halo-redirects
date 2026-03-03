package run.halo.redirects.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.SettingFetcher;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.manager.RedirectRuleRegistry;
import run.halo.redirects.util.RedirectRuleFileCodec;
import run.halo.redirects.util.RedirectRuleSupport;

@Component
@RestController
@RequestMapping("/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects")
public class RedirectRuleConsoleEndpoint {
    private static final String CONFIG_MAP_NAME = "redirects-config";
    private static final String SETTINGS_GROUP = "basic";
    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final SettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;

    public RedirectRuleConsoleEndpoint(SettingFetcher settingFetcher, ReactiveExtensionClient client) {
        this.settingFetcher = settingFetcher;
        this.client = client;
        this.objectMapper = new ObjectMapper();
    }

    @GetMapping("/settings")
    public SettingsPayload getSettings() {
        return toPayload(currentSettings());
    }

    @PutMapping(value = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SettingsPayload> updateSettings(@RequestBody SettingsPayload payload) {
        var settings = new RedirectSettings();
        settings.setEnabled(payload.enabled());
        settings.setPreserveQueryString(payload.preserveQueryString());
        settings.setBulkRules(null);
        settings.setRules(new ArrayList<>(payload.rules() == null ? List.of() : payload.rules()));

        return saveSettings(settings).thenReturn(toPayload(settings));
    }

    @PostMapping(value = "/rules/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ImportResult> importRules(@RequestPart("file") FilePart filePart,
        @RequestParam(name = "mode", defaultValue = "replace") String rawMode) {
        var mode = ImportMode.from(rawMode);

        return readContent(filePart)
            .map(content -> new UploadedFile(filePart.filename(), content))
            .flatMap(uploadedFile -> Mono.fromCallable(
                    () -> RedirectRuleFileCodec.importRules(uploadedFile.filename(), uploadedFile.content()))
                .onErrorMap(IllegalArgumentException.class, ex -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, ex.getMessage(), ex)))
            .flatMap(importedRules -> persistImportedRules(importedRules, mode));
    }

    @GetMapping("/rules/export")
    public Mono<ResponseEntity<byte[]>> exportRules(
        @RequestParam(name = "format", defaultValue = "csv") String format) {
        return Mono.fromCallable(() -> {
                var normalizedFormat = RedirectRuleFileCodec.normalizeExportFormat(format);
                var rules = RedirectRuleSupport.collectRules(currentSettings());
                var body = RedirectRuleFileCodec.exportRules(rules, normalizedFormat);

                return ResponseEntity.ok()
                    .contentType("xlsx".equals(normalizedFormat) ? XLSX_MEDIA_TYPE
                        : new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"redirect-rules." + normalizedFormat + "\"")
                    .body(body);
            })
            .onErrorMap(IllegalArgumentException.class, ex -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    private Mono<ImportResult> persistImportedRules(List<RedirectSettings.RedirectRule> importedRules,
        ImportMode mode) {
        if (importedRules.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The uploaded file does not contain any valid redirect rules"));
        }

        var settings = currentSettings();
        if (mode == ImportMode.REPLACE) {
            settings.setBulkRules(null);
            settings.setRules(new ArrayList<>(importedRules));
        } else {
            var mergedRules = new ArrayList<RedirectSettings.RedirectRule>();
            if (settings.getRules() != null) {
                mergedRules.addAll(settings.getRules());
            }
            mergedRules.addAll(importedRules);
            settings.setRules(mergedRules);
        }

        var totalRules = RedirectRuleSupport.collectRules(settings).size();
        return saveSettings(settings)
            .thenReturn(new ImportResult(importedRules.size(), totalRules, mode.value()));
    }

    private Mono<Void> saveSettings(RedirectSettings settings) {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Redirects config map was not found")))
            .flatMap(configMap -> Mono.fromCallable(() -> serialize(configMap, settings))
                .flatMap(updatedConfigMap -> client.update(updatedConfigMap)))
            .doOnSuccess(ignored -> RedirectRuleRegistry.reload(settings))
            .onErrorMap(JsonProcessingException.class, ex -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Unable to serialize redirect settings", ex))
            .then();
    }

    private ConfigMap serialize(ConfigMap configMap, RedirectSettings settings)
        throws JsonProcessingException {
        var data = configMap.getData() == null
            ? new LinkedHashMap<String, String>()
            : new LinkedHashMap<>(configMap.getData());

        data.put(SETTINGS_GROUP, objectMapper.writeValueAsString(settings));
        configMap.setData(data);
        return configMap;
    }

    private RedirectSettings currentSettings() {
        return settingFetcher.fetch(SETTINGS_GROUP, RedirectSettings.class)
            .orElseGet(RedirectSettings::new);
    }

    private SettingsPayload toPayload(RedirectSettings settings) {
        return new SettingsPayload(
            Boolean.TRUE.equals(settings.getEnabled()),
            Boolean.TRUE.equals(settings.getPreserveQueryString()),
            new ArrayList<>(RedirectRuleSupport.collectRules(settings))
        );
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

    private record UploadedFile(String filename, byte[] content) {
    }

    public record ImportResult(int importedCount, int totalRuleCount, String mode) {
    }

    public record SettingsPayload(boolean enabled, boolean preserveQueryString,
                                  List<RedirectSettings.RedirectRule> rules) {
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
