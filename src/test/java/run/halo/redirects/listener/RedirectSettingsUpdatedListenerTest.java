package run.halo.redirects.listener;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.SettingFetcher;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.manager.RedirectRuleRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedirectSettingsUpdatedListenerTest {
    @AfterEach
    void tearDown() {
        RedirectRuleRegistry.clear();
    }

    @Test
    void shouldReloadRulesFromLatestConfigMap() {
        var settingFetcher = mock(SettingFetcher.class);
        var client = mock(ReactiveExtensionClient.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher, client);
        var configMap = new ConfigMap();
        configMap.setData(Map.of("basic",
            "{\"enabled\":true,\"preserveQueryString\":true,"
                + "\"rules\":[{\"fromPath\":\"/legacy\",\"toPath\":\"/latest\",\"statusCode\":301}]}"));

        when(client.fetch(eq(ConfigMap.class), eq("redirects-config")))
            .thenReturn(Mono.just(configMap));
        when(settingFetcher.fetch(eq("basic"), eq(RedirectSettings.class)))
            .thenReturn(Optional.empty());

        listener.onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isPresent());
    }

    @Test
    void shouldFallBackToSettingsFetcherWhenConfigMapIsMissing() {
        var settingFetcher = mock(SettingFetcher.class);
        var client = mock(ReactiveExtensionClient.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher, client);
        var settings = enabledSettings(List.of(rule("/legacy", "/latest", 301)));

        when(settingFetcher.fetch(eq("basic"), eq(RedirectSettings.class)))
            .thenReturn(Optional.of(settings));
        when(client.fetch(eq(ConfigMap.class), eq("redirects-config")))
            .thenReturn(Mono.empty());

        listener.onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertTrue(RedirectRuleRegistry.resolve("/legacy", null).isPresent());
    }

    @Test
    void shouldClearRulesWhenSettingsAreMissing() {
        RedirectRuleRegistry.reload(enabledSettings(List.of(rule("/legacy", "/latest", 301))));

        var settingFetcher = mock(SettingFetcher.class);
        var client = mock(ReactiveExtensionClient.class);
        var listener = new RedirectSettingsUpdatedListener(settingFetcher, client);

        when(settingFetcher.fetch(eq("basic"), eq(RedirectSettings.class)))
            .thenReturn(Optional.empty());
        when(client.fetch(eq(ConfigMap.class), eq("redirects-config")))
            .thenReturn(Mono.empty());

        listener.onApplicationEvent(RedirectSettingsUpdatedEvent.trigger(this));

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    private RedirectSettings enabledSettings(List<RedirectSettings.RedirectRule> rules) {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setRules(rules);
        return settings;
    }

    private RedirectSettings.RedirectRule rule(String from, String to, int statusCode) {
        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(from);
        rule.setToPath(to);
        rule.setStatusCode(statusCode);
        return rule;
    }
}
