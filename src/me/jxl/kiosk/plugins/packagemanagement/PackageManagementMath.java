// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, device-free validation and parsing — no network, no root, no
 * Android APIs. Kept separate from {@link PackageManagementPlugin} so
 * it's unit-testable without a device; see test/PackageManagementTest.java.
 */
final class PackageManagementMath {
    private PackageManagementMath() {}

    // Package names: dot-separated segments, each starting with a letter
    // (a bare "android" is a real, valid package name with no dot at
    // all — one segment is enough). Same shape ha-paneld's own
    // AndroidInput.isPackage validates against.
    private static final Pattern PACKAGE_NAME =
        Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*");

    static boolean isValidPackageName(String pkg) {
        return pkg != null && pkg.length() <= 255 && PACKAGE_NAME.matcher(pkg).matches();
    }

    /** HTTPS only — the same rule ha-paneld's own downloader enforces:
     *  refusing plaintext at the source closes an https→http redirect
     *  downgrade before it can ever leak the request or waste a download. */
    static boolean isHttpsUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            return "https".equals(new URL(url).getProtocol());
        } catch (Exception e) {
            return false;
        }
    }

    /** Resolves a same-or-cross-host redirect [location] against [base],
     *  returning it only if the result is HTTPS — null means refuse
     *  (non-HTTPS target or unparseable), matching [isHttpsUrl]'s rule
     *  applied to every redirect hop, not just the origin URL. */
    static URL httpsRedirect(URL base, String location) {
        try {
            URL next = new URL(base, location);
            return "https".equals(next.getProtocol()) ? next : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Packages that can never be tamed or uninstalled, regardless of what
    // the user lists — the framework, system UI, settings, telephony, and
    // this app itself (both identities: Kiosk Satellite's real package ID,
    // and this plugin's own reasoning for why it must protect its host).
    private static final Set<String> CRITICAL = new LinkedHashSet<>(java.util.Arrays.asList(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "me.jxl.kiosk_satellite"
    ));

    static boolean isCritical(String pkg) {
        return CRITICAL.contains(pkg);
    }

    /** Parses a space/comma separated package list, validating each
     *  entry, deduplicating, and dropping anything critical — so the
     *  parsed list and what actually gets tamed are always identical
     *  (never silently disagree about whether a critical entry survived). */
    static List<String> parseTameList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        Set<String> seen = new LinkedHashSet<>();
        for (String token : raw.split("[\\s,]+")) {
            if (token.isEmpty()) continue;
            if (!isValidPackageName(token)) continue;
            if (isCritical(token)) continue;
            if (seen.add(token)) result.add(token);
        }
        return result;
    }
}
