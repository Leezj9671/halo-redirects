package run.halo.redirects.util;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.redirects.config.RedirectSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedirectRuleFileCodecTest {
    @Test
    void shouldImportCsvWithHeader() {
        var rules = RedirectRuleFileCodec.importRules("redirects.csv", """
            fromPath,toPath,statusCode,note,matchType
            /docs,/knowledge,301,docs migration,DIRECTORY
            /old-post,/new-post,302,,EXACT
            """.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, rules.size());
        assertEquals("/docs", rules.get(0).getFromPath());
        assertEquals("DIRECTORY", rules.get(0).getMatchType());
        assertEquals(302, rules.get(1).getStatusCode());
    }

    @Test
    void shouldRoundTripCsvIncludingGoneRules() {
        var rules = List.of(
            rule("/docs", "/knowledge", 301, "DIRECTORY"),
            rule("/旧文章", "/新文章, 第二版", 308, "EXACT"),
            rule("/deleted", null, 410, "EXACT")
        );

        var exported = RedirectRuleFileCodec.exportRules(rules, "csv");
        var imported = RedirectRuleFileCodec.importRules("redirects.csv", exported);

        assertEquals(3, imported.size());
        assertEquals("/knowledge", imported.get(0).getToPath());
        assertEquals("DIRECTORY", imported.get(0).getMatchType());
        assertEquals("/新文章, 第二版", imported.get(1).getToPath());
        assertEquals(308, imported.get(1).getStatusCode());
        assertEquals(410, imported.get(2).getStatusCode());
        assertNull(imported.get(2).getToPath());
    }

    @Test
    void shouldRejectXlsx() {
        assertThrows(IllegalArgumentException.class,
            () -> RedirectRuleFileCodec.importRules("redirects.xlsx", new byte[0]));
        assertThrows(IllegalArgumentException.class,
            () -> RedirectRuleFileCodec.exportRules(List.of(), "xlsx"));
    }

    private RedirectSettings.RedirectRule rule(String from, String to, int statusCode,
        String matchType) {
        var rule = new RedirectSettings.RedirectRule();
        rule.setFromPath(from);
        rule.setToPath(to);
        rule.setStatusCode(statusCode);
        rule.setMatchType(matchType);
        return rule;
    }
}
