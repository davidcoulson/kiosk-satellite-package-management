// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Known-working System WebView builds, so updating a Play-less panel is a
 * dropdown pick instead of a hunt for an APK. Pure data plus lookup — no
 * shell, no PluginHost; see test/WebViewPresetsTest.java.
 *
 * Same SDK 1 constraint as {@link TamePresets}: a `select`'s options are
 * fixed in the manifest, so this is a curated list rather than anything
 * queried at runtime. The **System WebView APK (URL)** text field stays,
 * and overrides a pick when non-empty.
 *
 * <h2>Why this list exists, and where it comes from</h2>
 *
 * Panels without Google Play ship years-old WebView and have no update
 * path, which is exactly what makes a Home Assistant dashboard feel
 * broken on them. The builds below are ha-paneld's
 * <a href="https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror">Panel
 * WebView mirror</a> — versions its maintainer verified on real panels and
 * mirrored as GitHub Release assets specifically to give them durable
 * links. Unlike the tame presets, this <em>is</em> a real upstream catalog
 * rather than something assembled here; the per-panel mapping in the
 * labels is theirs.
 *
 * Two things to understand before picking one:
 *
 * <ul>
 *   <li><b>These are not Google-signed.</b> They're LineageOS and Cromite
 *       <em>SystemWebView</em> builds, which deliberately use the
 *       {@code com.android.webview} package so Android selects them as the
 *       provider automatically. That is the documented approach for
 *       Play-less panels, not a workaround — but it is a different signer
 *       than stock, so a panel that still gets WebView through Play should
 *       use Play instead of this.</li>
 *   <li><b>The build must match both the ABI and the Android version.</b>
 *       An NSPanel Pro on Android 8.1 caps at WebView 138; newer builds
 *       refuse to install. A mismatch fails the install rather than
 *       damaging anything, so a wrong pick is recoverable — but WebView is
 *       what renders the dashboard, so verify the panel still loads after
 *       changing it.</li>
 * </ul>
 *
 * URLs point at a third party's release assets. If that release is ever
 * retagged or removed, a pick here fails as a download error — visible and
 * non-destructive — and the text field remains as the way through.
 */
final class WebViewPresets {
    private WebViewPresets() {}

    /** Manifest-facing label for "use the URL field instead", and the default. */
    static final String NONE = "None";

    private static final String MIRROR =
        "https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/";

    // Ordered oldest-Android first, since the panels that need this at all
    // are the old ones — the newest build is rarely the right answer here.
    private static final Map<String, String> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("LineageOS 138.0.7204.63 - arm64, Android 8.1 (NSPanel Pro)",
            MIRROR + "lineageos-webview-138.0.7204.63.apk");
        PRESETS.put("LineageOS 150.0.7871.63 - arm64, newer Android",
            MIRROR + "lineageos-webview-150.0.7871.63-arm64.apk");
        PRESETS.put("LineageOS 150.0.7871.63 - arm 32-bit, newer Android",
            MIRROR + "lineageos-webview-150.0.7871.63-arm.apk");
        PRESETS.put("Cromite 147.0.7727.56 - arm 32-bit, Android 11+ (TPA10)",
            MIRROR + "cromite-webview-147.0.7727.56.apk");
    }

    /** Dropdown options for the manifest, {@link #NONE} first. */
    static List<String> optionLabels() {
        List<String> labels = new java.util.ArrayList<>();
        labels.add(NONE);
        labels.addAll(PRESETS.keySet());
        return labels;
    }

    /** The download URL a label maps to — empty for {@link #NONE}, an
     *  unknown label, or null. An unrecognised label resolves to empty
     *  rather than throwing, so a manifest/code version skew installs
     *  nothing instead of installing something unintended. */
    static String urlFor(String label) {
        if (label == null) return "";
        String url = PRESETS.get(label);
        return url == null ? "" : url;
    }

    /** Read-only view, for tests and diagnostics. */
    static Map<String, String> all() {
        return Collections.unmodifiableMap(PRESETS);
    }
}
