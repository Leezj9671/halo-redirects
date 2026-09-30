package run.halo.redirects.listener;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.config.RedirectSettingsLoader;
import run.halo.redirects.manager.RedirectRuleRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedirectSettingsUpdatedListenerTest {

    @AfterEach
    void tearDown() {
        RedirectRuleRegistry.clear();
    }

    @Test
    void shouldReloadRulesFromConfigMapOnStartupEvent() {
        var client = clientReturning(configMap(enabledSettings(rule("/legacy", "/latest", 301))));

        listener(client).onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isPresent());
    }

    @Test
    void shouldClearRulesWhenConfigMapIsMissing() {
        RedirectRuleRegistry.reload(enabledSettings(rule("/legacy", "/latest", 301)));
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(RedirectSettingsLoader.CONFIG_MAP_NAME)))
            .thenReturn(Mono.empty());

        listener(client).onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    @Test
    void shouldClearRulesWhenBasicGroupIsMissing() {
        RedirectRuleRegistry.reload(enabledSettings(rule("/legacy", "/latest", 301)));
        var configMap = new ConfigMap();
        configMap.setData(Map.of("other", "{}"));

        listener(clientReturning(configMap))
            .onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    @Test
    void shouldReloadRulesFromConfigMapOnPluginConfigUpdatedEvent() {
        var client = clientReturning(configMap(enabledSettings(rule("/blog/old", "/blog/new", 302))));

        // The event payload is ignored on purpose: since Halo 2.25 it carries Jackson 3 nodes.
        listener(client).onPluginConfigUpdated(configUpdatedEvent(new PluginSource("redirects")));

        var resolved = RedirectRuleRegistry.resolve("/blog/old", null);
        assertTrue(resolved.isPresent());
        assertEquals(302, resolved.get().statusCode());
        assertEquals("/blog/new", resolved.get().location());
    }

    @Test
    void shouldReloadWhenEventSourceDoesNotExposePluginName() {
        var client = clientReturning(configMap(enabledSettings(rule("/a", "/b", 301))));

        listener(client).onPluginConfigUpdated(configUpdatedEvent(new Object()));

        assertTrue(RedirectRuleRegistry.resolve("/a", null).isPresent());
    }

    @Test
    void shouldClearRulesWhenSettingsAreDisabled() {
        RedirectRuleRegistry.reload(enabledSettings(rule("/legacy", "/latest", 301)));
        var disabled = enabledSettings(rule("/legacy", "/latest", 301));
        disabled.setEnabled(false);

        listener(clientReturning(configMap(disabled)))
            .onPluginConfigUpdated(configUpdatedEvent(new PluginSource("redirects")));

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    @Test
    void shouldKeepPreviousRulesWhenStoredJsonIsInvalid() {
        RedirectRuleRegistry.reload(enabledSettings(rule("/legacy", "/latest", 301)));
        var configMap = new ConfigMap();
        configMap.setData(Map.of(RedirectSettingsLoader.SETTINGS_GROUP, "{not json"));

        listener(clientReturning(configMap))
            .onPluginConfigUpdated(configUpdatedEvent(new PluginSource("redirects")));

        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isPresent());
    }

    @Test
    void shouldIgnorePluginConfigEventsFromOtherPlugins() {
        var client = mock(ReactiveExtensionClient.class);

        listener(client).onPluginConfigUpdated(configUpdatedEvent(new PluginSource("other-plugin")));

        verify(client, never()).fetch(eq(ConfigMap.class), eq(RedirectSettingsLoader.CONFIG_MAP_NAME));
    }

    private static RedirectSettingsUpdatedListener listener(ReactiveExtensionClient client) {
        return new RedirectSettingsUpdatedListener(new RedirectSettingsLoader(client));
    }

    private static ReactiveExtensionClient clientReturning(ConfigMap configMap) {
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(RedirectSettingsLoader.CONFIG_MAP_NAME)))
            .thenReturn(Mono.just(configMap));
        return client;
    }

    private static ConfigMap configMap(RedirectSettings settings) {
        var configMap = new ConfigMap();
        configMap.setData(Map.of(RedirectSettingsLoader.SETTINGS_GROUP,
            RedirectSettingsLoader.write(settings)));
        return configMap;
    }

    private static PluginConfigUpdatedEvent configUpdatedEvent(Object source) {
        return PluginConfigUpdatedEvent.builder()
            .source(source)
            .oldConfig(Map.of())
            .newConfig(Map.of())
            .build();
    }

    private static RedirectSettings enabledSettings(RedirectSettings.RedirectRule... rules) {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(true);
        settings.setRules(List.of(rules));
        return settings;
    }

    private static RedirectSettings.RedirectRule rule(String fromPath, String toPath, int statusCode) {
        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(fromPath);
        rule.setToPath(toPath);
        rule.setStatusCode(statusCode);
        return rule;
    }

    private static final class PluginSource {
        @SuppressWarnings("unused")
        private final String pluginName;

        private PluginSource(String pluginName) {
            this.pluginName = pluginName;
        }
    }
}
