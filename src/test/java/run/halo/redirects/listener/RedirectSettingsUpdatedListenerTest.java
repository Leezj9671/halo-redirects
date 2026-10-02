package run.halo.redirects.listener;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.config.RedirectSettingsLoader;
import run.halo.redirects.extension.RedirectRule;
import run.halo.redirects.manager.RedirectRuleRegistry;
import run.halo.redirects.service.RedirectRuleReloader;
import run.halo.redirects.service.RedirectRuleService;
import run.halo.redirects.support.InMemoryExtensionClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.spy;

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
        listener(spy(new InMemoryExtensionClient())).onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    @Test
    void shouldClearRulesWhenBasicGroupIsMissing() {
        RedirectRuleRegistry.reload(enabledSettings(rule("/legacy", "/latest", 301)));
        var configMap = configMap(new RedirectSettings());
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
        var configMap = configMap(new RedirectSettings());
        configMap.setData(Map.of(RedirectSettingsLoader.SETTINGS_GROUP, "{not json"));

        listener(clientReturning(configMap))
            .onPluginConfigUpdated(configUpdatedEvent(new PluginSource("redirects")));

        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isPresent());
    }

    @Test
    void shouldIgnorePluginConfigEventsFromOtherPlugins() {
        var client = spy(new InMemoryExtensionClient());

        listener(client).onPluginConfigUpdated(configUpdatedEvent(new PluginSource("other-plugin")));

        verify(client, never()).fetch(eq(ConfigMap.class), eq(RedirectSettingsLoader.CONFIG_MAP_NAME));
    }

    @Test
    void shouldMoveLegacyRulesIntoExtensions() {
        var client = clientReturning(configMap(enabledSettings(rule("/legacy", "/latest", 301))));
        var listener = listener(client);

        listener.onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        var migrated = client.all(RedirectRule.class);
        assertEquals(1, migrated.size());
        assertEquals("/legacy", migrated.get(0).getSpec().getFromPath());
        var configMap = client.fetch(ConfigMap.class, RedirectSettingsLoader.CONFIG_MAP_NAME).block();
        var stored = RedirectSettingsLoader.parse(
            configMap.getData().get(RedirectSettingsLoader.SETTINGS_GROUP));
        assertNull(stored.getRules());
        assertTrue(stored.getEnabled());
        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isPresent());

        // Deleting the migrated rule must not bring it back on the next reload.
        client.delete(migrated.get(0)).block();
        listener.onPluginConfigUpdated(configUpdatedEvent(new PluginSource("redirects")));
        assertTrue(client.all(RedirectRule.class).isEmpty());
        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isEmpty());

        // Rules written into settings later (old script, old API) are moved as well, and a
        // rule with the same source updates the existing one instead of duplicating it.
        var later = enabledSettings(rule("/later", "/one", 301));
        configMap.setData(Map.of(RedirectSettingsLoader.SETTINGS_GROUP,
            RedirectSettingsLoader.write(later)));
        client.update(configMap).block();
        listener.onPluginConfigUpdated(configUpdatedEvent(new PluginSource("redirects")));
        later.setRules(List.of(rule("/later", "/two", 302)));
        configMap.setData(Map.of(RedirectSettingsLoader.SETTINGS_GROUP,
            RedirectSettingsLoader.write(later)));
        client.update(configMap).block();
        listener.onPluginConfigUpdated(configUpdatedEvent(new PluginSource("redirects")));

        var moved = client.all(RedirectRule.class);
        assertEquals(1, moved.size());
        assertEquals("/two", moved.get(0).getSpec().getToPath());
        assertEquals(302, RedirectRuleRegistry.resolve("/later", null).orElseThrow().statusCode());
    }

    private static RedirectSettingsUpdatedListener listener(ReactiveExtensionClient client) {
        var loader = new RedirectSettingsLoader(client);
        return new RedirectSettingsUpdatedListener(
            new RedirectRuleReloader(loader, new RedirectRuleService(client)));
    }

    private static InMemoryExtensionClient clientReturning(ConfigMap configMap) {
        var client = spy(new InMemoryExtensionClient());
        client.create(configMap).block();
        return client;
    }

    private static ConfigMap configMap(RedirectSettings settings) {
        var configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(RedirectSettingsLoader.CONFIG_MAP_NAME);
        configMap.setMetadata(metadata);
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
