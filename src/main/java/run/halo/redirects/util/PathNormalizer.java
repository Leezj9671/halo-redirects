package run.halo.redirects.util;

import java.net.URI;

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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

