package run.halo.redirects.manager;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import run.halo.redirects.config.RedirectSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectRuleRegistryTest {
    @AfterEach
    void tearDown() {
        RedirectRuleRegistry.clear();
    }

    @Test
    void shouldResolveRedirectAndPreserveQueryString() {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(true);
        settings.setRules(List.of(rule("/old-post/", "/new-post/", 301)));

        RedirectRuleRegistry.reload(settings);

        var redirect = RedirectRuleRegistry.resolve("/old-post", "utm_source=test");

        assertTrue(redirect.isPresent());
        assertEquals("/new-post?utm_source=test", redirect.get().location());
        assertEquals(301, redirect.get().statusCode());
    }

    @Test
    void shouldIgnoreSelfRedirectRule() {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(false);
        settings.setRules(List.of(rule("/same", "/same/", 301)));

        RedirectRuleRegistry.reload(settings);

        assertFalse(RedirectRuleRegistry.isEnabled());
        assertTrue(RedirectRuleRegistry.resolve("/same", null).isEmpty());
    }

    @Test
    void shouldResolveBulkRulesConfiguredInTextarea() {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(true);
        settings.setBulkRules("""
            # legacy rules
            /promo -> /landing
            /docs-old,/docs-new,301,docs migration
            """);

        RedirectRuleRegistry.reload(settings);

        var promoRedirect = RedirectRuleRegistry.resolve("/promo", "utm_source=test");
        var docsRedirect = RedirectRuleRegistry.resolve("/docs-old", null);

        assertTrue(promoRedirect.isPresent());
        assertEquals("/landing?utm_source=test", promoRedirect.get().location());
        assertEquals(301, promoRedirect.get().statusCode());
        assertTrue(docsRedirect.isPresent());
        assertEquals("/docs-new", docsRedirect.get().location());
        assertEquals(301, docsRedirect.get().statusCode());
    }

    @Test
    void shouldPreferExplicitRulesOverBulkRulesForSamePath() {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(false);
        settings.setBulkRules("/same -> /from-bulk -> 302");
        settings.setRules(List.of(rule("/same", "/from-form", 301)));

        RedirectRuleRegistry.reload(settings);

        var redirect = RedirectRuleRegistry.resolve("/same", null);

        assertTrue(redirect.isPresent());
        assertEquals("/from-form", redirect.get().location());
        assertEquals(301, redirect.get().statusCode());
    }

    private RedirectSettings.RedirectRule rule(String from, String to, int statusCode) {
        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(from);
        rule.setToPath(to);
        rule.setStatusCode(statusCode);
        return rule;
    }
}
