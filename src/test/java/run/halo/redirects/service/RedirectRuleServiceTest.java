package run.halo.redirects.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.extension.RedirectRule;
import run.halo.redirects.support.InMemoryExtensionClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectRuleServiceTest {
    private final InMemoryExtensionClient client = new InMemoryExtensionClient();
    private final RedirectRuleService service = new RedirectRuleService(client);

    @Test
    void shouldCreateNormalizedRule() {
        var created = service.create(rule(" archives/旧文章 ", "/new", 999)).block();

        assertEquals("/archives/旧文章", created.getSpec().getFromPath());
        assertEquals(301, created.getSpec().getStatusCode());
        assertEquals("EXACT", created.getSpec().getMatchType());
        assertTrue(created.getSpec().getEnabled());
        assertTrue(created.getMetadata().getName().startsWith("redirect-rule-"));
    }

    @Test
    void shouldRejectDuplicateSourceIncludingEncodedAndTrailingSlash() {
        service.create(rule("/archives/旧文章", "/a", 301)).block();

        var ex = assertThrows(ResponseStatusException.class,
            () -> service.create(rule("/archives/%E6%97%A7%E6%96%87%E7%AB%A0/", "/b", 301)).block());
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());

        // Same path with a different match type is a different rule.
        var directory = rule("/archives/旧文章", "/c", 301);
        directory.setMatchType("DIRECTORY");
        service.create(directory).block();
        assertEquals(2, client.all(RedirectRule.class).size());
    }

    @Test
    void shouldAllowUpdatingARuleWithoutConflictingWithItself() {
        var created = service.create(rule("/old", "/a", 301)).block();

        var updated = service.update(created.getMetadata().getName(), rule("/old/", "/b", 302)).block();

        assertEquals("/b", updated.getSpec().getToPath());
        assertEquals(302, updated.getSpec().getStatusCode());
    }

    @Test
    void shouldValidateTargets() {
        var missing = assertThrows(ResponseStatusException.class,
            () -> service.create(rule("/a", " ", 301)).block());
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

        var self = assertThrows(ResponseStatusException.class,
            () -> service.create(rule("/same", "/same/?x=1", 301)).block());
        assertEquals(HttpStatus.BAD_REQUEST, self.getStatusCode());

        var gone = service.create(rule("/gone", "/ignored", 410)).block();
        assertNull(gone.getSpec().getToPath());
    }

    @Test
    void shouldUpsertByKeyAndCountInvalidEntries() {
        service.create(rule("/a", "/one", 301)).block();

        var result = service.upsertAll(List.of(
            rule("/a/", "/two", 302),
            rule("/b", "/x", 301),
            rule("/b", "/y", 301),
            rule("/invalid", null, 301))).block();

        assertEquals(1, result.created());
        assertEquals(2, result.updated());
        assertEquals(1, result.skipped());
        var rules = client.all(RedirectRule.class);
        assertEquals(2, rules.size());
        assertEquals("/two", rules.get(0).getSpec().getToPath());
        assertEquals("/y", rules.get(1).getSpec().getToPath());
    }

    @Test
    void shouldReplaceAllRules() {
        service.create(rule("/a", "/one", 301)).block();

        service.replaceAll(List.of(rule("/z", "/zz", 301))).block();

        var rules = client.all(RedirectRule.class);
        assertEquals(1, rules.size());
        assertEquals("/z", rules.get(0).getSpec().getFromPath());
    }

    private static RedirectSettings.RedirectRule rule(String from, String to, int statusCode) {
        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(from);
        rule.setToPath(to);
        rule.setStatusCode(statusCode);
        return rule;
    }
}
