package run.halo.redirects.endpoint;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedirectRuleConsoleEndpointTest {
    @Test
    void shouldParseTestUrlInAnyCommonShape() {
        var full = RedirectRuleConsoleEndpoint.parseTestUrl("https://blog.example.com/archives/旧文章?utm=中");
        assertEquals("/archives/旧文章", full.path());
        assertEquals("utm=%E4%B8%AD", full.rawQuery());

        var encoded = RedirectRuleConsoleEndpoint.parseTestUrl("/archives/%E6%97%A7");
        assertEquals("/archives/旧", encoded.path());
        assertNull(encoded.rawQuery());

        assertEquals("/docs/a b", RedirectRuleConsoleEndpoint.parseTestUrl("docs/a b").path());
        assertEquals("/", RedirectRuleConsoleEndpoint.parseTestUrl("https://example.com").path());
        assertThrows(ResponseStatusException.class, () -> RedirectRuleConsoleEndpoint.parseTestUrl(" "));
    }
}
