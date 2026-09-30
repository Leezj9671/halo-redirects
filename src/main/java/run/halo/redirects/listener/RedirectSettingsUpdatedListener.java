package run.halo.redirects.listener;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.redirects.config.RedirectSettingsLoader;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectSettingsUpdatedListener
    implements ApplicationListener<RedirectSettingsUpdatedEvent> {
    private static final Logger log =
        LoggerFactory.getLogger(RedirectSettingsUpdatedListener.class);
    private static final String PLUGIN_NAME = "redirects";

    private final RedirectSettingsLoader settingsLoader;

    public RedirectSettingsUpdatedListener(RedirectSettingsLoader settingsLoader) {
        this.settingsLoader = settingsLoader;
    }

    /**
     * Handles plugin startup.
     */
    @Override
    public void onApplicationEvent(RedirectSettingsUpdatedEvent event) {
        reload("startup").subscribe();
    }

    /**
     * Handles settings changed via Halo console. The event payload is only used as a trigger:
     * its node type differs between Halo versions (Jackson 2 vs Jackson 3), so the settings are
     * re-read from the config map instead.
     */
    @EventListener(PluginConfigUpdatedEvent.class)
    public void onPluginConfigUpdated(PluginConfigUpdatedEvent event) {
        if (!belongsToCurrentPlugin(event)) {
            return;
        }
        reload("config update").subscribe();
    }

    Mono<Void> reload(String trigger) {
        return settingsLoader.load()
            .doOnNext(settings -> {
                RedirectRuleRegistry.reload(settings);
                log.info("[redirects] {}: loaded {} active redirect rule(s)", trigger,
                    RedirectRuleRegistry.size());
            })
            .doOnError(ex -> log.warn("[redirects] {}: failed to reload redirect settings, "
                + "keeping previous rules", trigger, ex))
            .onErrorResume(ex -> Mono.empty())
            .then();
    }

    /**
     * Events are published into this plugin's own context, so every event is ours unless the
     * source explicitly names another plugin. Reloading is idempotent, so erring on the side of
     * reloading is harmless.
     */
    private boolean belongsToCurrentPlugin(PluginConfigUpdatedEvent event) {
        var pluginName = readField(event.getSource(), "pluginName");
        return pluginName == null || PLUGIN_NAME.equals(pluginName);
    }

    private Object readField(Object source, String fieldName) {
        for (Class<?> type = source == null ? null : source.getClass(); type != null;
             type = type.getSuperclass()) {
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
