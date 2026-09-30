package run.halo.redirects.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedirectSettingsLoaderTest {

    @Test
    void shouldIgnoreUnknownProperties() {
        var settings = RedirectSettingsLoader.parse(
            "{\"enabled\":true,\"empty\":false,\"rules\":[{\"fromPath\":\"/a\",\"toPath\":\"/b\","
                + "\"statusCode\":301,\"extra\":1}]}");

        assertTrue(settings.getEnabled());
        assertEquals("/a", settings.getRules().get(0).getFromPath());
    }

    @Test
    void shouldReturnEmptySettingsForBlankJson() {
        assertNull(RedirectSettingsLoader.parse("  ").getEnabled());
        assertNull(RedirectSettingsLoader.parse(null).getEnabled());
    }

    @Test
    void shouldRejectInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> RedirectSettingsLoader.parse("{oops"));
    }

    @Test
    void shouldSaveBasicGroupAndKeepOtherGroups() {
        var client = mock(ReactiveExtensionClient.class);
        var configMap = new ConfigMap();
        configMap.setData(new LinkedHashMap<>(Map.of("other", "{\"x\":1}")));
        when(client.fetch(eq(ConfigMap.class), eq(RedirectSettingsLoader.CONFIG_MAP_NAME)))
            .thenReturn(Mono.just(configMap));
        when(client.update(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setRules(List.of());
        var saved = new RedirectSettingsLoader(client).save(settings).block();

        assertEquals("{\"x\":1}", saved.getData().get("other"));
        assertTrue(RedirectSettingsLoader.parse(
            saved.getData().get(RedirectSettingsLoader.SETTINGS_GROUP)).getEnabled());
    }
}
