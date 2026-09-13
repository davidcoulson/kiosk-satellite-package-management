// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    /** Manifest-facing label meaning "work it out from this panel's hardware".
     *  Resolved at install time from getDeviceInfo, not baked into the
     *  manifest — SDK 1 select options are static, but what a label *means*
     *  need not be. */
    static final String AUTO = "Recommended for this panel";

    private static final String MIRROR =
        "https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/";

    /** A build, the hardware it suits, and the Android range it is the right
     *  answer for. {@code maxSdk} is Integer.MAX_VALUE where nothing caps it. */
    static final class Build {
        final String label;
        final String url;
        final String abi;
        final int minSdk;
        final int maxSdk;

        /** The dotted version out of the label, e.g. "150.0.7871.63" from
         *  "LineageOS 150.0.7871.63 - arm64, newer Android". The label is
         *  the catalogue's own source of truth for the version, so the
         *  version is read from it rather than duplicated beside it. */
        String version() {
            for (String word : label.split("[ -]")) {
                if (word.length() > 2 && word.indexOf('.') > 0
                    && Character.isDigit(word.charAt(0))) {
                    return word;
                }
            }
            return "";
        }

        Build(String label, String url, String abi, int minSdk, int maxSdk) {
            this.label = label;
            this.url = url;
            this.abi = abi;
            this.minSdk = minSdk;
            this.maxSdk = maxSdk;
        }

        boolean suits(String primaryAbi, int sdkInt) {
            return abi.equals(primaryAbi) && sdkInt >= minSdk && sdkInt <= maxSdk;
        }
    }

    // Ordered so the first entry that suits a panel is the right answer for
    // it. That matters for 32-bit Android 11+, where both the Cromite build
    // and the newer LineageOS one are installable: ha-paneld maps the TPA10
    // to Cromite, so Cromite is listed first and wins.
    private static final List<Build> BUILDS = Collections.unmodifiableList(Arrays.asList(
        // Android 8.1 caps here. ha-paneld's NSPanel Pro (px30) guidance, and
        // the version an NSPanel Pro tested here is already running.
        new Build("LineageOS 138.0.7204.63 - arm64, Android 8.1 (NSPanel Pro)",
            MIRROR + "lineageos-webview-138.0.7204.63.apk", "arm64-v8a", 26, 27),
        new Build("LineageOS 150.0.7871.63 - arm64, newer Android",
            MIRROR + "lineageos-webview-150.0.7871.63-arm64.apk", "arm64-v8a", 28, Integer.MAX_VALUE),
        new Build("Cromite 147.0.7727.56 - arm 32-bit, Android 11+ (TPA10)",
            MIRROR + "cromite-webview-147.0.7727.56.apk", "armeabi-v7a", 30, Integer.MAX_VALUE),
        new Build("LineageOS 150.0.7871.63 - arm 32-bit, newer Android",
            MIRROR + "lineageos-webview-150.0.7871.63-arm.apk", "armeabi-v7a", 28, 29)));

    /** Dropdown options for the manifest: {@link #NONE}, {@link #AUTO}, then
     *  every build in catalog order. */
    static List<String> optionLabels() {
        List<String> labels = new ArrayList<>();
        labels.add(NONE);
        labels.add(AUTO);
        for (Build build : BUILDS) labels.add(build.label);
        return labels;
    }

    /** The download URL a label maps to — empty for {@link #NONE},
     *  {@link #AUTO} (which needs hardware to resolve; see
     *  {@link #recommend}), an unknown label, or null. An unrecognised label
     *  resolves to empty rather than throwing, so a manifest/code version
     *  skew installs nothing instead of installing something unintended. */
    static String urlFor(String label) {
        Build build = buildFor(label);
        return build == null ? "" : build.url;
    }

    /** The ABI a labelled build needs, or empty where the label names no
     *  specific build. */
    static String abiFor(String label) {
        Build build = buildFor(label);
        return build == null ? "" : build.abi;
    }

    static Build buildFor(String label) {
        if (label == null) return null;
        for (Build build : BUILDS) {
            if (build.label.equals(label)) return build;
        }
        return null;
    }

    /** The build this panel should get, or null when nothing in the catalog
     *  suits it — an ABI we carry no build for, or an Android version older
     *  than anything here. Null is a refusal to guess: installing the wrong
     *  WebView replaces what renders the dashboard, so "no recommendation"
     *  has to be an available answer.
     *
     *  <p>Matching uses the panel's <em>primary</em> ABI (first entry of
     *  getDeviceInfo's {@code abis}, which Android returns in preference
     *  order) rather than any ABI it can run. A 64-bit panel lists
     *  armeabi-v7a too, and picking a 32-bit WebView for it would install
     *  something that runs but is not what the platform wants. */
    static Build recommend(List<String> abis, int sdkInt) {
        return recommend(abis, sdkInt, null);
    }

    /**
     * As above, but never recommends a build the panel is already past.
     *
     * The catalogue is a fixed list and a panel is not: one here runs
     * 153.0.8010.36 while the newest catalogued build for its ABI is
     * 150.0.7871.63, so the unguarded recommendation was pointing at a
     * downgrade -- which Android refuses anyway, making it advice that
     * could only ever waste someone's time.
     *
     * An unreadable or unparseable installed version recommends as before:
     * not knowing what is installed is not a reason to recommend nothing.
     */
    static Build recommend(List<String> abis, int sdkInt, String installedVersion) {
        if (abis == null || abis.isEmpty() || sdkInt <= 0) return null;
        String primary = abis.get(0);
        for (Build build : BUILDS) {
            if (!build.suits(primary, sdkInt)) continue;
            if (isNewer(installedVersion, build.version())) continue;
            return build;
        }
        return null;
    }

    /** True when [installed] is a version at least as new as [candidate].
     *  Either being absent or unparseable answers false -- an unknown
     *  cannot be shown to be newer. */
    static boolean isNewer(String installed, String candidate) {
        int[] a = parts(installed);
        int[] b = parts(candidate);
        if (a == null || b == null) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return true;   // identical: already on it, so not worth recommending
    }

    private static int[] parts(String version) {
        if (version == null) return null;
        String trimmed = version.trim();
        if (trimmed.isEmpty()) return null;
        String[] bits = trimmed.split("\\.");
        int[] out = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            try {
                out[i] = Integer.parseInt(bits[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** Whether a panel can install a labelled build at all — the build's ABI
     *  appears anywhere in the panel's list. Deliberately looser than
     *  {@link #recommend}: an explicit pick is someone overriding the
     *  recommendation on purpose, and only an outright impossible install is
     *  worth refusing. */
    static boolean canInstall(String label, List<String> abis) {
        String needed = abiFor(label);
        return needed.isEmpty() || (abis != null && abis.contains(needed));
    }

    /** Read-only view, for tests and diagnostics. */
    static List<Build> all() {
        return BUILDS;
    }

    /** The version out of a dumpsys "versionName=..." line, or null when
     *  the package is absent (dumpsys prints nothing) or the line is not
     *  the shape expected. Trailing fields are dropped: some builds print
     *  more on the same line. */
    static String parseVersionName(String dumpsysLine) {
        if (dumpsysLine == null) return null;
        int at = dumpsysLine.indexOf("versionName=");
        if (at < 0) return null;
        String rest = dumpsysLine.substring(at + "versionName=".length()).trim();
        if (rest.isEmpty()) return null;
        int space = rest.indexOf(' ');
        if (space > 0) rest = rest.substring(0, space);
        return rest.isEmpty() || "null".equals(rest) ? null : rest;
    }
}
