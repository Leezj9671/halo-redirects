package run.halo.redirects.util;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.redirects.config.RedirectSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void shouldExportAndImportXlsx() {
        var rules = List.of(
            rule("/docs", "/knowledge", 301, "DIRECTORY"),
            rule("/old-post", "/new-post", 302, "EXACT")
        );

        var exported = RedirectRuleFileCodec.exportRules(rules, "xlsx");
        var imported = RedirectRuleFileCodec.importRules("redirects.xlsx", exported);

        assertEquals(2, imported.size());
        assertEquals("/knowledge", imported.get(0).getToPath());
        assertEquals("DIRECTORY", imported.get(0).getMatchType());
        assertEquals(302, imported.get(1).getStatusCode());
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
