package run.halo.redirects.endpoint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.config.RedirectSettingsLoader;
import run.halo.redirects.manager.RedirectRuleRegistry;
import run.halo.redirects.util.RedirectRuleFileCodec;
import run.halo.redirects.util.RedirectRuleSupport;

@Component
@RestController
@RequestMapping("/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects")
public class RedirectRuleConsoleEndpoint {
    private final RedirectSettingsLoader settingsLoader;

    public RedirectRuleConsoleEndpoint(RedirectSettingsLoader settingsLoader) {
        this.settingsLoader = settingsLoader;
    }

    @GetMapping("/settings")
    public Mono<SettingsPayload> getSettings() {
        return settingsLoader.load().map(this::toPayload);
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
        return settingsLoader.load()
            .map(settings -> {
                var normalizedFormat = RedirectRuleFileCodec.normalizeExportFormat(format);
                var rules = RedirectRuleSupport.collectRules(settings);
                var body = RedirectRuleFileCodec.exportRules(rules, normalizedFormat);

                return ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
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

        return settingsLoader.load().flatMap(settings -> {
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
        });
    }

    private Mono<Void> saveSettings(RedirectSettings settings) {
        return settingsLoader.save(settings)
            .doOnNext(saved -> RedirectRuleRegistry.reload(settings))
            .onErrorMap(IllegalStateException.class, ex -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, ex.getMessage(), ex))
            .onErrorMap(IllegalArgumentException.class, ex -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex))
            .then();
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
