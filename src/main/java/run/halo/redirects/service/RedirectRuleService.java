package run.halo.redirects.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.extension.RedirectRule;
import run.halo.redirects.util.PathNormalizer;
import run.halo.redirects.util.RedirectRuleSupport;

/**
 * Reads and writes {@link RedirectRule} extensions. All writes validate and normalize the rule,
 * and refuse a second rule for the same source path and match type.
 */
@Component
public class RedirectRuleService {
    static final String GENERATED_NAME_PREFIX = "redirect-rule-";

    private final ReactiveExtensionClient client;

    public RedirectRuleService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * All rules, oldest first. When two rules share a source the later one wins, matching the
     * order users created them in.
     */
    public Flux<RedirectRule> listAll() {
        // Deleting an extension first only stamps metadata.deletionTimestamp; such rules are
        // already gone from the user's point of view.
        return client.listAll(RedirectRule.class, new ListOptions(),
                Sort.by(Sort.Order.asc("metadata.creationTimestamp"),
                    Sort.Order.asc("metadata.name")))
            .filter(rule -> rule.getMetadata().getDeletionTimestamp() == null);
    }

    public Mono<List<RedirectSettings.RedirectRule>> listDefinitions() {
        return listAll().map(RedirectRuleService::toDefinition).collectList();
    }

    public Mono<RedirectRule> create(RedirectSettings.RedirectRule input) {
        var spec = validate(input);
        return ensureUnique(null, spec).then(Mono.defer(() -> {
            var rule = new RedirectRule();
            var metadata = new Metadata();
            metadata.setGenerateName(GENERATED_NAME_PREFIX);
            rule.setMetadata(metadata);
            rule.setSpec(spec);
            return client.create(rule);
        }));
    }

    public Mono<RedirectRule> update(String name, RedirectSettings.RedirectRule input) {
        var spec = validate(input);
        return client.fetch(RedirectRule.class, name)
            .switchIfEmpty(Mono.error(notFound(name)))
            .flatMap(existing -> ensureUnique(name, spec).then(Mono.defer(() -> {
                existing.setSpec(spec);
                return client.update(existing);
            })));
    }

    public Mono<Void> delete(String name) {
        return client.fetch(RedirectRule.class, name)
            .switchIfEmpty(Mono.error(notFound(name)))
            .flatMap(client::delete)
            .then();
    }

    public Mono<Integer> deleteAll(List<String> names) {
        return Flux.fromIterable(names == null ? List.<String>of() : names)
            .concatMap(name -> client.fetch(RedirectRule.class, name).flatMap(client::delete))
            .count()
            .map(Long::intValue);
    }

    /**
     * Adds rules, updating an existing rule that has the same source path and match type.
     * Invalid entries are skipped and counted.
     */
    public Mono<WriteResult> upsertAll(List<RedirectSettings.RedirectRule> inputs) {
        return listAll().collectList().flatMap(existingRules -> {
            var byKey = new HashMap<String, RedirectRule>();
            existingRules.forEach(rule -> byKey.put(keyOf(rule.getSpec()), rule));
            var counts = new int[3];

            return Flux.fromIterable(inputs)
                .concatMap(input -> {
                    RedirectRule.Spec spec;
                    try {
                        spec = validate(input);
                    } catch (ResponseStatusException ex) {
                        counts[2]++;
                        return Mono.empty();
                    }
                    var existing = byKey.get(keyOf(spec));
                    if (existing != null) {
                        counts[1]++;
                        existing.setSpec(spec);
                        return client.update(existing).doOnNext(saved -> byKey.put(keyOf(spec), saved));
                    }
                    counts[0]++;
                    var rule = new RedirectRule();
                    var metadata = new Metadata();
                    metadata.setGenerateName(GENERATED_NAME_PREFIX);
                    rule.setMetadata(metadata);
                    rule.setSpec(spec);
                    return client.create(rule).doOnNext(saved -> byKey.put(keyOf(spec), saved));
                })
                .then(Mono.fromSupplier(() -> new WriteResult(counts[0], counts[1], counts[2])));
        });
    }

    /**
     * Replaces every rule with the given ones.
     */
    public Mono<WriteResult> replaceAll(List<RedirectSettings.RedirectRule> inputs) {
        return listAll().concatMap(client::delete).then(upsertAll(inputs));
    }

    /**
     * Writes a rule moved out of the legacy settings: updates the rule with the same source
     * path and match type if there is one, otherwise creates it under a name derived from that
     * key, so a retried move never duplicates rules. Emits false for an invalid rule.
     */
    public Mono<Boolean> upsertLegacy(RedirectSettings.RedirectRule input) {
        RedirectRule.Spec spec;
        try {
            spec = validate(input);
        } catch (ResponseStatusException ex) {
            return Mono.just(false);
        }
        var key = keyOf(spec);
        return listAll()
            .filter(rule -> key.equals(keyOf(rule.getSpec())))
            .next()
            .flatMap(existing -> {
                existing.setSpec(spec);
                return client.update(existing).thenReturn(true);
            })
            .switchIfEmpty(Mono.defer(() -> {
                var rule = new RedirectRule();
                var metadata = new Metadata();
                metadata.setName("redirect-rule-migrated-" + shortHash(key));
                rule.setMetadata(metadata);
                rule.setSpec(spec);
                return client.create(rule).thenReturn(true);
            }));
    }

    public static RedirectSettings.RedirectRule toDefinition(RedirectRule rule) {
        var spec = rule.getSpec() == null ? new RedirectRule.Spec() : rule.getSpec();
        var definition = new RedirectSettings.RedirectRule();
        definition.setName(rule.getMetadata().getName());
        definition.setFromPath(spec.getFromPath());
        definition.setToPath(spec.getToPath());
        definition.setMatchType(RedirectRuleSupport.normalizeMatchType(spec.getMatchType()));
        definition.setStatusCode(RedirectRuleSupport.normalizeStatusCode(spec.getStatusCode()));
        definition.setNote(spec.getNote());
        definition.setEnabled(!Boolean.FALSE.equals(spec.getEnabled()));
        return definition;
    }

    /**
     * Checks and normalizes user input; throws 400 with a readable message when invalid.
     */
    static RedirectRule.Spec validate(RedirectSettings.RedirectRule input) {
        if (input == null) {
            throw badRequest("规则内容不能为空");
        }
        var fromPath = trimToNull(input.getFromPath());
        if (fromPath == null || PathNormalizer.normalizePath(PathNormalizer.decodePath(fromPath)) == null) {
            throw badRequest("来源路径不能为空");
        }
        if (PathNormalizer.isAbsoluteUrl(fromPath)) {
            fromPath = PathNormalizer.normalizePath(fromPath);
        }
        if (!fromPath.startsWith("/")) {
            fromPath = "/" + fromPath;
        }

        var statusCode = RedirectRuleSupport.normalizeStatusCode(input.getStatusCode());
        var toPath = trimToNull(input.getToPath());
        if (RedirectRuleSupport.isGone(statusCode)) {
            toPath = null;
        } else if (toPath == null) {
            throw badRequest("目标地址不能为空（只有 410 可以留空）");
        } else if (PathNormalizer.normalizeTarget(toPath) == null) {
            throw badRequest("目标地址无效：" + toPath);
        }

        var spec = new RedirectRule.Spec();
        spec.setFromPath(fromPath);
        spec.setToPath(toPath);
        spec.setStatusCode(statusCode);
        spec.setMatchType(RedirectRuleSupport.normalizeMatchType(input.getMatchType()));
        spec.setNote(trimToNull(input.getNote()));
        spec.setEnabled(!Boolean.FALSE.equals(input.getEnabled()));

        if (toPath != null && PathNormalizer.isLocalPath(toPath)
            && Objects.equals(sourceKey(fromPath), sourceKey(PathNormalizer.normalizeTarget(toPath)))) {
            throw badRequest("目标地址和来源路径相同，会造成循环");
        }
        return spec;
    }

    private Mono<Void> ensureUnique(String selfName, RedirectRule.Spec spec) {
        var key = keyOf(spec);
        return listAll()
            .filter(rule -> !Objects.equals(rule.getMetadata().getName(), selfName))
            .filter(rule -> key.equals(keyOf(rule.getSpec())))
            .next()
            .flatMap(duplicate -> Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "已存在相同来源路径的规则：" + duplicate.getSpec().getFromPath())))
            .then();
    }

    static String keyOf(RedirectRule.Spec spec) {
        if (spec == null) {
            return "";
        }
        return RedirectRuleSupport.normalizeMatchType(spec.getMatchType()) + " "
            + sourceKey(spec.getFromPath());
    }

    private static String sourceKey(String path) {
        var stripped = path == null ? null : path.replaceAll("[?#].*$", "");
        return PathNormalizer.normalizePath(PathNormalizer.decodePath(stripped));
    }

    private static String shortHash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在：" + name);
    }

    public record WriteResult(int created, int updated, int skipped) {
    }
}
