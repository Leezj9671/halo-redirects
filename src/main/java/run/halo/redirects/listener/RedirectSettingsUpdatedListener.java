package run.halo.redirects.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.app.plugin.SettingFetcher;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectSettingsUpdatedListener
    implements ApplicationListener<RedirectSettingsUpdatedEvent> {
    private static final Logger log =
        LoggerFactory.getLogger(RedirectSettingsUpdatedListener.class);
    private static final String PLUGIN_NAME = "redirects";
    private static final String CONFIG_MAP_NAME = "redirects-config";
    private static final String SETTINGS_GROUP = "basic";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SettingFetcher settingFetcher;

    public RedirectSettingsUpdatedListener(SettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    /**
     * Handles plugin startup: loads rules from SettingFetcher (safe on non-reactor thread).
     */
    @Override
    public void onApplicationEvent(RedirectSettingsUpdatedEvent event) {
        try {
            var settings = settingFetcher.fetch(SETTINGS_GROUP, RedirectSettings.class)
                .orElse(null);
            RedirectRuleRegistry.reload(settings);
            log.info("[redirects] loaded {} active redirect rule(s)", RedirectRuleRegistry.size());
        } catch (Exception ex) {
            log.warn("[redirects] failed to reload redirect settings on startup", ex);
        }
    }

    /**
     * Handles settings changed via Halo console: parses directly from event data
     * to avoid blocking calls on reactor thread.
     */
    @EventListener(PluginConfigUpdatedEvent.class)
    public void onPluginConfigUpdated(PluginConfigUpdatedEvent event) {
        if (!belongsToCurrentPlugin(event)) {
            return;
        }

        try {
            var basicNode = extractSettingsNode(event);

            if (basicNode == null || basicNode.isNull() || basicNode.isMissingNode()) {
                RedirectRuleRegistry.clear();
                log.info("[redirects] config updated but no '{}' group found, rules cleared",
                    SETTINGS_GROUP);
                return;
            }

            var settings = MAPPER.treeToValue(basicNode, RedirectSettings.class);
            RedirectRuleRegistry.reload(settings);
            log.info("[redirects] config updated via Halo, reloaded {} rule(s)",
                RedirectRuleRegistry.size());
        } catch (Exception ex) {
            log.warn("[redirects] failed to reload on config update event", ex);
        }
    }

    private boolean belongsToCurrentPlugin(PluginConfigUpdatedEvent event) {
        var source = event.getSource();
        if (source == null) {
            return false;
        }

        if (source == settingFetcher) {
            return true;
        }

        var delegateFetcher = readField(settingFetcher, "delegateFetcher");
        if (delegateFetcher == source) {
            return true;
        }

        return PLUGIN_NAME.equals(readField(source, "pluginName"))
            || CONFIG_MAP_NAME.equals(readField(source, "configMapName"));
    }

    private JsonNode extractSettingsNode(PluginConfigUpdatedEvent event) throws Exception {
        var settingValues = extractSettingValues(event);
        if (settingValues == null) {
            return null;
        }

        var basicValue = settingValues.get(SETTINGS_GROUP);
        if (basicValue == null) {
            return null;
        }

        if (basicValue instanceof JsonNode jsonNode) {
            if (!jsonNode.isTextual()) {
                return jsonNode;
            }

            var normalized = jsonNode.asText().trim();
            return normalized.isEmpty() ? null : MAPPER.readTree(normalized);
        }

        if (basicValue instanceof CharSequence rawJson) {
            var normalized = rawJson.toString().trim();
            return normalized.isEmpty() ? null : MAPPER.readTree(normalized);
        }

        return MAPPER.valueToTree(basicValue);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSettingValues(PluginConfigUpdatedEvent event) {
        try {
            var method = PluginConfigUpdatedEvent.class.getMethod("getNewSettingValues");
            var value = method.invoke(event);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (NoSuchMethodException ignored) {
            // Halo < 2.23 only exposes getNewConfig().
        } catch (Exception ex) {
            log.debug("[redirects] failed to inspect new setting values, falling back to raw config",
                ex);
        }

        var newConfig = event.getNewConfig();
        if (newConfig instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return null;
    }

    private Object readField(Object source, String fieldName) {
        if (source == null) {
            return null;
        }

        for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(source);
            } catch (NoSuchFieldException ignored) {
                // Continue walking up the hierarchy.
            } catch (Exception ex) {
                log.debug("[redirects] failed to read field '{}' from event source", fieldName, ex);
                return null;
            }
        }

        return null;
    }
}
