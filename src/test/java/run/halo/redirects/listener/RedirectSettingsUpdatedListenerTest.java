package run.halo.redirects.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.app.plugin.SettingFetcher;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.manager.RedirectRuleRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedirectSettingsUpdatedListenerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        RedirectRuleRegistry.clear();
    }

    @Test
    void shouldReloadRulesFromSettingFetcherOnStartupEvent() {
        var settingFetcher = mock(SettingFetcher.class);
        var settings = enabledSettings(List.of(rule("/legacy", "/latest", 301)));
        when(settingFetcher.fetch(eq("basic"), eq(RedirectSettings.class)))
            .thenReturn(Optional.of(settings));

        var listener = new RedirectSettingsUpdatedListener(settingFetcher);
        listener.onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isPresent());
    }

    @Test
    void shouldClearRulesWhenSettingsAreMissing() {
        RedirectRuleRegistry.reload(enabledSettings(List.of(rule("/legacy", "/latest", 301))));

        var settingFetcher = mock(SettingFetcher.class);
        when(settingFetcher.fetch(eq("basic"), eq(RedirectSettings.class)))
            .thenReturn(Optional.empty());

        var listener = new RedirectSettingsUpdatedListener(settingFetcher);
        listener.onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    @Test
    void shouldReloadRulesOnPluginConfigUpdatedEvent() {
        var settingFetcher = mock(SettingFetcher.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher);

        var settings = enabledSettings(List.of(rule("/blog/old", "/blog/new", 302)));
        var basicJson = TextNode.valueOf(writeSettings(settings));

        var event = PluginConfigUpdatedEvent.builder()
            .source(new PluginConfigSource("redirects", "redirects-config"))
            .oldConfig(Map.of())
            .newConfig(Map.of("basic", basicJson))
            .build();

        listener.onPluginConfigUpdated(event);

        var resolved = RedirectRuleRegistry.resolve("/blog/old", null);
        assertTrue(resolved.isPresent());
        assertEquals(302, resolved.get().statusCode());
        assertEquals("/blog/new", resolved.get().location());
    }

    @Test
    void shouldClearRulesWhenPluginConfigUpdatedWithNoBasicGroup() {
        RedirectRuleRegistry.reload(enabledSettings(List.of(rule("/old", "/new", 301))));
        assertTrue(RedirectRuleRegistry.isEnabled());

        var settingFetcher = mock(SettingFetcher.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher);

        var event = PluginConfigUpdatedEvent.builder()
            .source(new PluginConfigSource("redirects", "redirects-config"))
            .oldConfig(Map.of())
            .newConfig(Map.of())
            .build();

        listener.onPluginConfigUpdated(event);

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    @Test
    void shouldClearRulesWhenPluginConfigUpdatedWithDisabledSettings() {
        RedirectRuleRegistry.reload(enabledSettings(List.of(rule("/old", "/new", 301))));
        assertTrue(RedirectRuleRegistry.isEnabled());

        var settingFetcher = mock(SettingFetcher.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher);

        var disabledSettings = new RedirectSettings();
        disabledSettings.setEnabled(false);
        disabledSettings.setRules(List.of(rule("/old", "/new", 301)));
        var basicJson = TextNode.valueOf(writeSettings(disabledSettings));

        var event = PluginConfigUpdatedEvent.builder()
            .source(new PluginConfigSource("redirects", "redirects-config"))
            .oldConfig(Map.of())
            .newConfig(Map.of("basic", basicJson))
            .build();

        listener.onPluginConfigUpdated(event);

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    @Test
    void shouldStillReloadRulesWhenPluginConfigAlreadyProvidesJsonNode() {
        var settingFetcher = mock(SettingFetcher.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher);

        var settings = enabledSettings(List.of(rule("/legacy/path", "/modern/path", 301)));
        var basicNode = MAPPER.valueToTree(settings);

        var event = PluginConfigUpdatedEvent.builder()
            .source(new PluginConfigSource("redirects", "redirects-config"))
            .oldConfig(Map.of())
            .newConfig(Map.of("basic", basicNode))
            .build();

        listener.onPluginConfigUpdated(event);

        var resolved = RedirectRuleRegistry.resolve("/legacy/path", null);
        assertTrue(resolved.isPresent());
        assertEquals("/modern/path", resolved.get().location());
    }

    @Test
    void shouldIgnorePluginConfigEventsFromOtherPlugins() {
        RedirectRuleRegistry.reload(enabledSettings(List.of(rule("/kept", "/target", 301))));

        var settingFetcher = mock(SettingFetcher.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher);

        var event = PluginConfigUpdatedEvent.builder()
            .source(new PluginConfigSource("other-plugin", "other-config"))
            .oldConfig(Map.of())
            .newConfig(Map.of())
            .build();

        listener.onPluginConfigUpdated(event);

        var resolved = RedirectRuleRegistry.resolve("/kept", null);
        assertTrue(resolved.isPresent());
        assertEquals("/target", resolved.get().location());
    }

    private RedirectSettings enabledSettings(List<RedirectSettings.RedirectRule> rules) {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setRules(rules);
        return settings;
    }

    private String writeSettings(RedirectSettings settings) {
        try {
            return MAPPER.writeValueAsString(settings);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private RedirectSettings.RedirectRule rule(String from, String to, int statusCode) {
        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(from);
        rule.setToPath(to);
        rule.setStatusCode(statusCode);
        return rule;
    }

    private static final class PluginConfigSource {
        private final String pluginName;
        private final String configMapName;

        private PluginConfigSource(String pluginName, String configMapName) {
            this.pluginName = pluginName;
            this.configMapName = configMapName;
        }
    }
}
