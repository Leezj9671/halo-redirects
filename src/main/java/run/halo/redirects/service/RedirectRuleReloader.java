package run.halo.redirects.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.config.RedirectSettingsLoader;
import run.halo.redirects.manager.RedirectRuleRegistry;
import run.halo.redirects.util.RedirectRuleSupport;

/**
 * Rebuilds the in-memory {@link RedirectRuleRegistry} from the settings and the
 * {@code RedirectRule} extensions. Bursts of change notifications (for example one per rule
 * when Halo starts) are coalesced into a single reload.
 */
@Component
public class RedirectRuleReloader {
    private static final Logger log = LoggerFactory.getLogger(RedirectRuleReloader.class);
    private static final Duration DEBOUNCE = Duration.ofMillis(200);

    private final RedirectSettingsLoader settingsLoader;
    private final RedirectRuleService ruleService;
    private final Sinks.Many<String> requests = Sinks.many().multicast().onBackpressureBuffer();
    private final Disposable subscription;

    public RedirectRuleReloader(RedirectSettingsLoader settingsLoader,
        RedirectRuleService ruleService) {
        this.settingsLoader = settingsLoader;
        this.ruleService = ruleService;
        this.subscription = requests.asFlux()
            .sampleTimeout(trigger -> Mono.delay(DEBOUNCE))
            .concatMap(trigger -> reloadNow(trigger).onErrorResume(ex -> Mono.empty()))
            .subscribe();
    }

    /**
     * Schedules a reload; several requests within a short window result in one reload.
     */
    public void requestReload(String trigger) {
        requests.emitNext(trigger, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
    }

    /**
     * Reloads right away; completes once the new rules are active.
     */
    public Mono<Void> reloadNow(String trigger) {
        return migrateLegacyRules()
            .then(Mono.defer(() -> Mono.zip(settingsLoader.load(), ruleService.listDefinitions())))
            .doOnNext(tuple -> {
                var settings = tuple.getT1();
                var effective = new RedirectSettings();
                effective.setEnabled(settings.getEnabled());
                effective.setPreserveQueryString(settings.getPreserveQueryString());
                // Rules still sitting in settings (migration failed or not run yet) keep working;
                // extensions come later so they win for the same source.
                var rules = new ArrayList<>(RedirectRuleSupport.collectRules(settings));
                rules.addAll(tuple.getT2());
                effective.setRules(rules);
                RedirectRuleRegistry.reload(effective);
                log.info("[redirects] {}: loaded {} active redirect rule(s)", trigger,
                    RedirectRuleRegistry.size());
            })
            .doOnError(ex -> log.warn("[redirects] {}: failed to reload redirect rules, "
                + "keeping previous rules", trigger, ex))
            .then();
    }

    /**
     * Before 0.3.0 rules lived in the plugin settings. Whenever the settings still hold such
     * rules (an upgrade, or an old script writing the old format) they are moved into
     * extensions and cleared from the settings, so every active rule shows up in the rules tab.
     */
    Mono<Void> migrateLegacyRules() {
        return settingsLoader.fetchConfigMap()
            .flatMap(configMap -> {
                var json = configMap.getData() == null ? null
                    : configMap.getData().get(RedirectSettingsLoader.SETTINGS_GROUP);
                var settings = RedirectSettingsLoader.parse(json);
                var legacyRules = RedirectRuleSupport.collectRules(settings);
                if (legacyRules.isEmpty()) {
                    return Mono.empty();
                }
                return Flux.fromIterable(legacyRules)
                    .concatMap(ruleService::upsertLegacy)
                    .filter(Boolean::booleanValue)
                    .count()
                    .flatMap(written -> settingsLoader.saveWithoutLegacyRules(configMap, settings)
                        .doOnNext(saved -> log.info("[redirects] moved {} of {} rule(s) from "
                            + "settings into the rules tab", written, legacyRules.size())));
            })
            .doOnError(ex -> log.warn("[redirects] failed to move rules out of settings; they "
                + "keep working from settings and the move is retried on the next reload", ex))
            .onErrorResume(ex -> Mono.empty())
            .then();
    }

    @PreDestroy
    void dispose() {
        subscription.dispose();
    }
}
