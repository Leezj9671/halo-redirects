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

    @Test
    void shouldDecodePercentEncodedRulePath() {
        assertEquals("/archives/中文", PathNormalizer.decodePath("/archives/%E4%B8%AD%E6%96%87"));
        assertEquals("/bad/%zz", PathNormalizer.decodePath("/bad/%zz"));
    }

    @Test
    void shouldEncodeDecodedSubPath() {
        assertEquals("/%E5%AD%90%20x%3F", PathNormalizer.encodePath("/子 x?"));
    }

    @Test
    void shouldMakeLocationHeaderSafe() {
        assertEquals("/archives/%E6%96%B0%E6%96%87%E7%AB%A0?q=1#%E9%94%9A",
            PathNormalizer.toHeaderValue("/archives/新文章?q=1#锚"));
        assertEquals("/already/%E4%B8%AD", PathNormalizer.toHeaderValue("/already/%E4%B8%AD"));
        assertEquals("https://xn--fiqs8s.example/%E8%B7%AF%E5%BE%84",
            PathNormalizer.toHeaderValue("https://中国.example/路径"));
    }
}
