package run.halo.redirects.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathNormalizerTest {
    @Test
    void shouldNormalizeTrailingSlashAndQuery() {
        assertEquals("/posts/hello", PathNormalizer.normalizePath("/posts/hello/?draft=true"));
    }

    @Test
    void shouldExtractPathFromAbsoluteUrl() {
        assertEquals("/legacy/post", PathNormalizer.normalizePath("https://example.com/legacy/post/?ref=1"));
    }

    @Test
    void shouldNormalizeRelativeTargetAndKeepQueryAndFragment() {
        assertEquals("/new-post?from=legacy#faq",
            PathNormalizer.normalizeTarget("new-post/?from=legacy#faq"));
    }

    @Test
    void shouldAppendQueryBeforeAnchor() {
        assertEquals("/new-post?utm_source=test#faq",
            PathNormalizer.appendRawQuery("/new-post#faq", "utm_source=test"));
    }
}
