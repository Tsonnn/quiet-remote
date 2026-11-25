package com.tom.tvremote;

/**
 * v48.1: startup update check — pure Java, no Android imports,
 * so it can be unit-tested on a plain JVM (see
 * test/UpdateCheckTest.java). The app asks GitHub for its
 * latest release (public API, no auth), compares the release
 * tag with the installed version, and MainActivity shows a
 * notice only when a newer version exists. No JSON library in
 * this app — the parsers are scoped string scans over the
 * small latest-release payload.
 */
public final class UpdateCheck {

    private UpdateCheck() {}

    /** GitHub latest-release endpoint for this repo. */
    public static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/Tsonnn/quiet-remote/releases/latest";

    /** True when the release tag is strictly newer than the
     *  installed version. A leading "v" is stripped; segments
     *  compare numerically, missing segments count as 0, so
     *  "v48.10" beats "48.9" and "48.1" ties "48.1.0". */
    public static boolean isNewerVersion(String latestTag, String installedVersion) {
        if (latestTag == null || installedVersion == null) return false;
        String[] latest = stripV(latestTag).split("\\.");
        String[] installed = stripV(installedVersion).split("\\.");
        int n = Math.max(latest.length, installed.length);
        for (int i = 0; i < n; i++) {
            int a = segment(latest, i);
            int b = segment(installed, i);
            if (a != b) return a > b;
        }
        return false;
    }

    private static String stripV(String v) {
        if (v.length() > 0 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) return v.substring(1);
        return v;
    }

    private static int segment(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The "tag_name" value of a GitHub latest-release payload,
     *  or null when the payload has none. */
    public static String parseLatestTag(String json) {
        if (json == null) return null;
        int at = json.indexOf("\"tag_name\"");
        return at < 0 ? null : valueAfter(json, at);
    }

    /** The "browser_download_url" of the asset whose "name" ends
     *  with ".apk", or null when no .apk asset is present.
     *  Relies on GitHub's asset field order (name before
     *  browser_download_url) — the URL must come before the
     *  next asset's "name", so a .zip's URL is never picked up
     *  for an .apk name, or vice versa. */
    public static String parseApkDownloadUrl(String json) {
        if (json == null) return null;
        int from = 0;
        while (true) {
            int nameAt = json.indexOf("\"name\"", from);
            if (nameAt < 0) return null;
            String name = valueAfter(json, nameAt);
            if (name == null) return null;
            int nameEnd = valueEnd(json, nameAt);
            if (nameEnd < 0) return null;
            int urlAt = json.indexOf("\"browser_download_url\"", nameEnd);
            int nextNameAt = json.indexOf("\"name\"", nameEnd);
            if (name.endsWith(".apk") && urlAt >= 0
                    && (nextNameAt < 0 || urlAt < nextNameAt)) {
                return valueAfter(json, urlAt);
            }
            from = nameEnd;
        }
    }

    /** The string value after the key at keyAt
     *  ("key": "value"), or null when there is none. */
    private static String valueAfter(String json, int keyAt) {
        int end = valueEnd(json, keyAt);
        if (end < 0) return null;
        int colon = json.indexOf(':', keyAt);
        int open = json.indexOf('"', colon + 1);
        return json.substring(open + 1, end - 1);
    }

    /** Index just past the closing quote of the string value
     *  that follows the key at keyAt, or -1. */
    private static int valueEnd(String json, int keyAt) {
        int colon = json.indexOf(':', keyAt);
        if (colon < 0) return -1;
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return -1;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return -1;
        return close + 1;
    }
}
