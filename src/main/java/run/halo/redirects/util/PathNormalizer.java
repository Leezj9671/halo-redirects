package run.halo.redirects.util;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

public final class PathNormalizer {
    private PathNormalizer() {
    }

    public static String normalizePath(String value) {
        if (!hasText(value)) {
            return null;
        }

        var normalized = value.trim();

        if (isAbsoluteUrl(normalized)) {
            try {
                normalized = URI.create(normalized).getPath();
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        if (!hasText(normalized)) {
            return "/";
        }

        var fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }

        var queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }

        if (!hasText(normalized)) {
            return "/";
        }

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    public static String normalizeTarget(String value) {
        if (!hasText(value)) {
            return null;
        }

        var normalized = value.trim();
        if (isAbsoluteUrl(normalized)) {
            return normalized;
        }

        var fragment = "";
        var fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            fragment = normalized.substring(fragmentIndex);
            normalized = normalized.substring(0, fragmentIndex);
        }

        var query = "";
        var queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            query = normalized.substring(queryIndex);
            normalized = normalized.substring(0, queryIndex);
        }

        var path = normalizePath(normalized);
        if (!hasText(path)) {
            return null;
        }

        return path + query + fragment;
    }

    public static boolean isAbsoluteUrl(String value) {
        return value != null
            && (value.startsWith("http://") || value.startsWith("https://"));
    }

    public static boolean isLocalPath(String value) {
        return value != null && !isAbsoluteUrl(value);
    }

    public static String appendRawQuery(String target, String rawQuery) {
        if (!hasText(target) || !hasText(rawQuery)) {
            return target;
        }

        var anchorIndex = target.indexOf('#');
        var beforeAnchor = anchorIndex >= 0 ? target.substring(0, anchorIndex) : target;
        var anchor = anchorIndex >= 0 ? target.substring(anchorIndex) : "";
        var separator = beforeAnchor.contains("?") ? "&" : "?";

        return beforeAnchor + separator + rawQuery + anchor;
    }

    /**
     * Decodes percent-escapes so a rule typed as {@code /a/%E4%B8%AD} matches the decoded request
     * path {@code /a/中}. Invalid escapes leave the value untouched.
     */
    public static String decodePath(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return UriUtils.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    /**
     * Encodes a decoded path fragment (such as the sub-path kept by a directory rule) so that
     * characters like space, {@code ?}, {@code #}, {@code %} and non-ASCII survive in a URL.
     */
    public static String encodePath(String decodedPath) {
        if (decodedPath == null || decodedPath.isEmpty()) {
            return decodedPath;
        }
        return UriUtils.encodePath(decodedPath, StandardCharsets.UTF_8);
    }

    /**
     * Makes a location safe for the {@code Location} header: HTTP headers are written as
     * ISO-8859-1, so non-ASCII and whitespace characters would otherwise be mangled into
     * {@code ?}. Existing percent-escapes are kept, and a non-ASCII host is converted to
     * punycode.
     */
    public static String toHeaderValue(String location) {
        if (location == null || location.isEmpty()) {
            return location;
        }

        var prefix = "";
        var rest = location;
        if (isAbsoluteUrl(location)) {
            var hostStart = location.indexOf("://") + 3;
            var hostEnd = hostStart;
            while (hostEnd < location.length() && "/?#".indexOf(location.charAt(hostEnd)) < 0) {
                hostEnd++;
            }
            prefix = location.substring(0, hostStart)
                + toAsciiAuthority(location.substring(hostStart, hostEnd));
            rest = location.substring(hostEnd);
        }

        return prefix + encodeUnsafeChars(rest);
    }

    private static String toAsciiAuthority(String authority) {
        if (isHeaderSafe(authority)) {
            return authority;
        }
        var at = authority.lastIndexOf('@');
        var userInfo = at >= 0 ? authority.substring(0, at + 1) : "";
        var hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        var colon = hostPort.lastIndexOf(':');
        var host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        var port = colon >= 0 ? hostPort.substring(colon) : "";
        try {
            host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException ex) {
            host = encodeUnsafeChars(host);
        }
        return encodeUnsafeChars(userInfo) + host + port;
    }

    private static String encodeUnsafeChars(String value) {
        if (isHeaderSafe(value)) {
            return value;
        }
        var builder = new StringBuilder(value.length() + 16);
        value.codePoints().forEach(codePoint -> {
            if (codePoint > 0x20 && codePoint < 0x7f) {
                builder.append((char) codePoint);
                return;
            }
            for (var b : new String(Character.toChars(codePoint))
                .getBytes(StandardCharsets.UTF_8)) {
                builder.append('%').append(String.format("%02X", b & 0xff));
            }
        });
        return builder.toString();
    }

    private static boolean isHeaderSafe(String value) {
        for (var i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            if (ch <= 0x20 || ch >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

