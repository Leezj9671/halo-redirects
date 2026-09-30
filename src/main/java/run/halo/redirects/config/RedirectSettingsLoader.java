package run.halo.redirects.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Reads and writes redirect settings straight from the plugin's own {@link ConfigMap}.
 *
 * <p>This deliberately avoids {@code SettingFetcher} and the payload of
 * {@code PluginConfigUpdatedEvent}: {@code SettingFetcher} turned from a class into an interface
 * in Halo 2.23, and since Halo 2.25 the event carries Jackson 3 ({@code tools.jackson}) nodes.
 * {@code ConfigMap#getData()} has stayed a plain {@code Map<String, String>} of JSON strings across
 * all supported Halo versions, so parsing it with our own Jackson 2 mapper is version-agnostic.
 */
@Component
public class RedirectSettingsLoader {
    public static final String CONFIG_MAP_NAME = "redirects-config";
    public static final String SETTINGS_GROUP = "basic";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReactiveExtensionClient client;

    public RedirectSettingsLoader(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * Loads the current settings; emits empty (disabled) settings when nothing is saved yet.
     */
    public Mono<RedirectSettings> load() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(ConfigMap::getData)
            .mapNotNull(data -> data.get(SETTINGS_GROUP))
            .map(RedirectSettingsLoader::parse)
            .defaultIfEmpty(new RedirectSettings());
    }

    /**
     * Stores the settings into the config map, keeping other groups untouched.
     */
    public Mono<ConfigMap> save(RedirectSettings settings) {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "Redirects config map was not found")))
            .flatMap(configMap -> {
                var data = configMap.getData() == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<>(configMap.getData());
                data.put(SETTINGS_GROUP, write(settings));
                configMap.setData(data);
                return client.update(configMap);
            });
    }

    public static RedirectSettings parse(String json) {
        if (json == null || json.isBlank()) {
            return new RedirectSettings();
        }
        try {
            var settings = MAPPER.readValue(json, RedirectSettings.class);
            return settings == null ? new RedirectSettings() : settings;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid redirect settings JSON", ex);
        }
    }

    public static String write(RedirectSettings settings) {
        try {
            return MAPPER.writeValueAsString(settings);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize redirect settings", ex);
        }
    }
}
