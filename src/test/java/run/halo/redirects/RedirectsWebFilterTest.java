package run.halo.redirects;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.listener.RedirectSettingsUpdatedEvent;
import run.halo.redirects.manager.RedirectRuleRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedirectsWebFilterTest {
    @AfterEach
    void tearDown() {
        RedirectRuleRegistry.clear();
    }

    @Test
    void shouldReturnConfiguredRedirect() {
        RedirectRuleRegistry.reload(settings(List.of(rule("/old-post", "/new-post", 301))));

        var publisher = mock(ApplicationEventPublisher.class);
        var filter = new RedirectsWebFilter(publisher);
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/old-post?utm_source=test").build()
        );
        WebFilterChain chain = unused -> Mono.error(new AssertionError("chain should not be called"));

        filter.filter(exchange, chain).block();

        assertEquals(301, exchange.getResponse().getStatusCode().value());
        assertEquals("/new-post?utm_source=test",
            exchange.getResponse().getHeaders().getFirst("Location"));
        assertEquals("no-cache, no-store, must-revalidate",
            exchange.getResponse().getHeaders().getFirst("Cache-Control"));
        verify(publisher, never()).publishEvent(any(RedirectSettingsUpdatedEvent.class));
    }

    @Test
    void shouldNotSkipRegularPathThatOnlyStartsWithApiLetters() {
        RedirectRuleRegistry.reload(settings(List.of(rule("/apiary", "/renamed-apiary", 301))));

        var publisher = mock(ApplicationEventPublisher.class);
        var filter = new RedirectsWebFilter(publisher);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/apiary").build());
        WebFilterChain chain = unused -> Mono.error(new AssertionError("chain should not be called"));

        filter.filter(exchange, chain).block();

        assertEquals(301, exchange.getResponse().getStatusCode().value());
        assertEquals("/renamed-apiary",
            exchange.getResponse().getHeaders().getFirst("Location"));
    }

    @Test
    void shouldSkipReservedApiPath() {
        RedirectRuleRegistry.reload(settings(List.of(rule("/api/posts", "/new-post", 301))));

        var chainCalled = new AtomicBoolean(false);
        var filter = new RedirectsWebFilter(mock(ApplicationEventPublisher.class));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/posts").build());
        WebFilterChain chain = unused -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertTrue(chainCalled.get());
        assertNull(exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getHeaders().containsKey("Location"));
    }

    @Test
    void shouldPublishEventAfterSettingsMutationRequest() {
        var publisher = mock(ApplicationEventPublisher.class);
        var filter = new RedirectsWebFilter(publisher);
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/apis/api.console.halo.run/v1alpha1/plugins/redirects/config")
                .build()
        );

        filter.filter(exchange, unused -> Mono.empty()).block();

        verify(publisher).publishEvent(any(RedirectSettingsUpdatedEvent.class));
    }

    @Test
    void shouldNotPublishEventForSettingsReadRequest() {
        var publisher = mock(ApplicationEventPublisher.class);
        var filter = new RedirectsWebFilter(publisher);
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/apis/api.console.halo.run/v1alpha1/plugins/redirects/config")
                .build()
        );

        filter.filter(exchange, unused -> Mono.empty()).block();

        verify(publisher, never()).publishEvent(any(RedirectSettingsUpdatedEvent.class));
    }

    private RedirectSettings settings(List<RedirectSettings.RedirectRule> rules) {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(true);
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
