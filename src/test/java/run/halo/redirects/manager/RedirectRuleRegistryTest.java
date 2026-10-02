package run.halo.redirects.manager;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import run.halo.redirects.config.RedirectSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void shouldResolveDirectoryRedirectAndKeepSubPath() {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(true);
        settings.setRules(List.of(rule("/docs", "/knowledge", 301, "DIRECTORY")));

        RedirectRuleRegistry.reload(settings);

        var redirect = RedirectRuleRegistry.resolve("/docs/guide/install", "utm_source=test");

        assertTrue(redirect.isPresent());
        assertEquals("/knowledge/guide/install?utm_source=test", redirect.get().location());
        assertEquals(301, redirect.get().statusCode());
    }

    @Test
    void shouldPreferExactRuleBeforeDirectoryRule() {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(false);
        settings.setRules(List.of(
            rule("/docs", "/knowledge", 301, "DIRECTORY"),
            rule("/docs", "/docs-home", 302, "EXACT")
        ));

        RedirectRuleRegistry.reload(settings);

        var redirect = RedirectRuleRegistry.resolve("/docs", null);

        assertTrue(redirect.isPresent());
        assertEquals("/docs-home", redirect.get().location());
        assertEquals(302, redirect.get().statusCode());
    }

    @Test
    void shouldMatchChineseRulesAndEncodeLocation() {
        RedirectRuleRegistry.reload(settings(true,
            rule("/archives/旧文章", "/archives/新文章", 301),
            rule("/archives/%E6%97%A7%E7%89%88", "/archives/新版", 301),
            rule("/旧目录", "/new-dir", 301, "DIRECTORY")));

        // WebFlux hands the filter a decoded path.
        assertEquals("/archives/%E6%96%B0%E6%96%87%E7%AB%A0",
            RedirectRuleRegistry.resolve("/archives/旧文章", null).orElseThrow().location());
        assertEquals("/archives/%E6%96%B0%E7%89%88",
            RedirectRuleRegistry.resolve("/archives/旧版", null).orElseThrow().location());
        assertEquals("/new-dir/%E5%AD%90%20x?utm=%E4%B8%AD",
            RedirectRuleRegistry.resolve("/旧目录/子 x", "utm=%E4%B8%AD").orElseThrow().location());
    }

    @Test
    void shouldResolveGoneAndPermanentRedirectCodes() {
        RedirectRuleRegistry.reload(settings(false,
            rule("/deleted", null, 410),
            rule("/moved", "/new", 308),
            rule("/temp", "/new", 307),
            rule("/unknown", "/new", 999)));

        var gone = RedirectRuleRegistry.resolve("/deleted", null).orElseThrow();
        assertEquals(410, gone.statusCode());
        assertNull(gone.location());
        assertEquals(308, RedirectRuleRegistry.resolve("/moved", null).orElseThrow().statusCode());
        assertEquals(307, RedirectRuleRegistry.resolve("/temp", null).orElseThrow().statusCode());
        assertEquals(301, RedirectRuleRegistry.resolve("/unknown", null).orElseThrow().statusCode());
    }

    @Test
    void shouldSkipRulesThatFormALoopButKeepTheRest() {
        RedirectRuleRegistry.reload(settings(false,
            rule("/a", "/b", 301),
            rule("/b", "/c", 301),
            rule("/c", "/a", 301),
            rule("/entry", "/a", 301),
            rule("/chain", "/b", 301),
            rule("/ok", "/fine", 301)));

        assertTrue(RedirectRuleRegistry.resolve("/a", null).isEmpty());
        assertTrue(RedirectRuleRegistry.resolve("/b", null).isEmpty());
        assertTrue(RedirectRuleRegistry.resolve("/c", null).isEmpty());
        // Rules leading into the loop are fine once the loop is gone.
        assertEquals("/a", RedirectRuleRegistry.resolve("/entry", null).orElseThrow().location());
        assertEquals("/b", RedirectRuleRegistry.resolve("/chain", null).orElseThrow().location());
        assertEquals("/fine", RedirectRuleRegistry.resolve("/ok", null).orElseThrow().location());
    }

    @Test
    void shouldSkipDirectoryRuleThatRedirectsIntoItself() {
        RedirectRuleRegistry.reload(settings(false,
            rule("/docs", "/docs/v2", 301, "DIRECTORY"),
            rule("/blog", "/posts", 301, "DIRECTORY"),
            rule("/posts/legacy", "/blog/legacy-archive", 301)));

        assertTrue(RedirectRuleRegistry.resolve("/docs/a", null).isEmpty());
        assertEquals("/posts/a", RedirectRuleRegistry.resolve("/blog/a", null).orElseThrow().location());
        // /posts/legacy -> /blog/legacy-archive -> /posts/legacy-archive ends, so both stay.
        assertEquals("/blog/legacy-archive",
            RedirectRuleRegistry.resolve("/posts/legacy", null).orElseThrow().location());
    }

    @Test
    void shouldDetectLoopThroughEncodedTarget() {
        RedirectRuleRegistry.reload(settings(false,
            rule("/中", "/x", 301),
            rule("/x", "/%E4%B8%AD", 301)));

        assertFalse(RedirectRuleRegistry.isEnabled());
    }

    private RedirectSettings settings(boolean preserveQueryString,
        RedirectSettings.RedirectRule... rules) {
        var settings = new RedirectSettings();
        settings.setEnabled(true);
        settings.setPreserveQueryString(preserveQueryString);
        settings.setRules(List.of(rules));
        return settings;
    }

    private RedirectSettings.RedirectRule rule(String from, String to, int statusCode) {
        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(from);
        rule.setToPath(to);
        rule.setStatusCode(statusCode);
        return rule;
    }

    private RedirectSettings.RedirectRule rule(String from, String to, int statusCode, String matchType) {
        var rule = rule(from, to, statusCode);
        rule.setMatchType(matchType);
        return rule;
    }
}
