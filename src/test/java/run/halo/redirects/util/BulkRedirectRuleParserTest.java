package run.halo.redirects.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BulkRedirectRuleParserTest {
    @Test
    void shouldParseArrowAndCsvRules() {
        var rules = BulkRedirectRuleParser.parse("""
            /old-a -> /new-a
            /old-b => /new-b => 302
            /old-c,/new-c,301,migrated,DIRECTORY
            /old-d -> /new-d -> 301 -> DIRECTORY
            """);

        assertEquals(4, rules.size());
        assertEquals("/old-a", rules.get(0).getFromPath());
        assertEquals("/new-a", rules.get(0).getToPath());
        assertEquals(301, rules.get(0).getStatusCode());
        assertEquals(302, rules.get(1).getStatusCode());
        assertEquals("migrated", rules.get(2).getNote());
        assertEquals("DIRECTORY", rules.get(2).getMatchType());
        assertEquals("DIRECTORY", rules.get(3).getMatchType());
    }

    @Test
    void shouldIgnoreCommentsAndInvalidLines() {
        var rules = BulkRedirectRuleParser.parse("""
            # comment
            invalid line
            /valid -> /target
            -> /missing-source
            """);

        assertEquals(1, rules.size());
        assertEquals("/valid", rules.get(0).getFromPath());
        assertEquals("/target", rules.get(0).getToPath());
    }
}
