package run.halo.redirects.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.SettingFetcher;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectSettingsUpdatedListener
    implements ApplicationListener<RedirectSettingsUpdatedEvent> {
    private static final Logger log =
        LoggerFactory.getLogger(RedirectSettingsUpdatedListener.class);
    private static final String CONFIG_MAP_NAME = "redirects-config";
    private static final String SETTINGS_GROUP = "basic";

    private final SettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;

    public RedirectSettingsUpdatedListener(SettingFetcher settingFetcher,
        ReactiveExtensionClient client) {
        this.settingFetcher = settingFetcher;
        this.client = client;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void onApplicationEvent(RedirectSettingsUpdatedEvent event) {
        loadLatestSettings()
            .defaultIfEmpty(new RedirectSettings())
            .subscribe(settings -> {
                RedirectRuleRegistry.reload(settings);
                log.info("[redirects] loaded {} active redirect rule(s)", RedirectRuleRegistry.size());
            }, error -> log.warn("[redirects] failed to reload redirect settings", error));
    }

    private Mono<RedirectSettings> loadLatestSettings() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(configMap -> Mono.justOrEmpty(deserialize(configMap)))
            .onErrorResume(error -> {
                log.warn("[redirects] failed to fetch redirect config map, falling back to setting fetcher",
                    error);
                return Mono.empty();
            })
            .switchIfEmpty(Mono.defer(
                () -> Mono.justOrEmpty(settingFetcher.fetch(SETTINGS_GROUP, RedirectSettings.class))));
    }

    private RedirectSettings deserialize(ConfigMap configMap) {
        if (configMap == null || configMap.getData() == null) {
            return null;
        }

        var rawSettings = configMap.getData().get(SETTINGS_GROUP);
        if (rawSettings == null || rawSettings.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(rawSettings, RedirectSettings.class);
        } catch (Exception ex) {
            log.warn("[redirects] failed to parse redirect settings from config map", ex);
            return null;
        }
    }
}
