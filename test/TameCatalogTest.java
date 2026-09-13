// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Device-free tests for the tame package catalog, the WebView preset
 *  catalog, and the preset+custom merge — reflected into since these live
 *  outside the plugin's package. */
public final class TameCatalogTest {
    public static void main(String[] args) throws Exception {
        Class<?> catalog = Class.forName("me.jxl.kiosk.plugins.packagemanagement.TameCatalog");
        Class<?> webview = Class.forName("me.jxl.kiosk.plugins.packagemanagement.WebViewPresets");
        Class<?> math = Class.forName("me.jxl.kiosk.plugins.packagemanagement.PackageManagementMath");

        Method entries = catalog.getDeclaredMethod("entries");
        entries.setAccessible(true);
        List<?> rows = (List<?>) entries.invoke(null);
        assertTrue(!rows.isEmpty(), "the catalog has entries");

        List<String> keys = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        for (Object row : rows) {
            keys.add(field(row, "settingKey"));
            packages.add(field(row, "pkg"));
            String title = field(row, "title");
            assertTrue(!title.isEmpty() && title.length() <= 80, "title fits SDK 1's limits: " + title);
        }
        assertEquals(keys.size(), new java.util.LinkedHashSet<>(keys).size(), "no duplicate setting keys");
        assertEquals(packages.size(), new java.util.LinkedHashSet<>(packages).size(), "no duplicate packages");

        // The network-critical package is excluded from the catalog: taming
        // it on a wired panel would strand it. See TameCatalog's class doc.
        assertTrue(!packages.contains("com.smartos.xinch.platform.ethernet"),
            "the ethernet platform package is never offered as a toggle");

        // Every catalog package must survive the same validation and
        // critical-package filtering a typed one goes through — otherwise a
        // toggle would be a control that silently does nothing.
        Method merge = math.getDeclaredMethod("mergeTameSources", List.class, String.class);
        merge.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> allSurvive = (List<String>) merge.invoke(null, packages, "");
        assertEquals(packages, allSurvive, "every catalog package passes validation and is not critical");

        Method selected = catalog.getDeclaredMethod("selectedPackages", Map.class);
        selected.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> noneOn = (List<String>) selected.invoke(null, new HashMap<String, Object>());
        assertTrue(noneOn.isEmpty(), "no toggles set tames nothing");
        @SuppressWarnings("unchecked")
        List<String> nullMap = (List<String>) selected.invoke(null, (Object) null);
        assertTrue(nullMap.isEmpty(), "a null settings map tames nothing rather than throwing");

        Map<String, Object> mixed = new HashMap<>();
        mixed.put(keys.get(0), true);
        mixed.put(keys.get(1), false);
        mixed.put("notASetting", true);
        mixed.put(keys.get(2), "true");   // a string, not a boolean
        @SuppressWarnings("unchecked")
        List<String> picked = (List<String>) selected.invoke(null, mixed);
        assertEquals(Arrays.asList(packages.get(0)), picked,
            "only genuinely-true toggles count; false, absent, and non-boolean values are off");

        Map<String, Object> allOn = new HashMap<>();
        for (String key : keys) allOn.put(key, true);
        @SuppressWarnings("unchecked")
        List<String> every = (List<String>) selected.invoke(null, allOn);
        assertEquals(packages, every, "all toggles on yields the catalog in display order");

        // --- WebView presets ---
        Method optionLabels = webview.getDeclaredMethod("optionLabels");
        optionLabels.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) optionLabels.invoke(null);
        assertEquals("None", labels.get(0), "None is first so it reads as the default");
        assertEquals("Recommended for this panel", labels.get(1), "auto is offered right after None");
        assertTrue(labels.size() >= 2 && labels.size() <= 32, "SDK 1 caps a select at 32 options");
        for (String l : labels) {
            assertTrue(!l.isEmpty() && l.length() <= 80, "each option fits SDK 1's 1..80 char limit: " + l);
        }

        Method urlFor = webview.getDeclaredMethod("urlFor", String.class);
        urlFor.setAccessible(true);
        assertEquals("", urlFor.invoke(null, "None"), "None resolves to no URL");
        assertEquals("", urlFor.invoke(null, "Recommended for this panel"),
            "auto has no URL of its own — it needs hardware to resolve");
        assertEquals("", urlFor.invoke(null, "Not A Real Build"),
            "an unknown label installs nothing rather than throwing");
        assertEquals("", urlFor.invoke(null, (Object) null), "null resolves to no URL");

        Method all = webview.getDeclaredMethod("all");
        all.setAccessible(true);
        List<?> builds = (List<?>) all.invoke(null);
        Method isHttps = math.getDeclaredMethod("isHttpsUrl", String.class);
        isHttps.setAccessible(true);
        for (Object build : builds) {
            String label = field(build, "label");
            String url = field(build, "url");
            assertEquals(url, urlFor.invoke(null, label), "each label resolves to its own URL: " + label);
            // runInstall refuses non-HTTPS, so a preset that isn't HTTPS
            // would be a dropdown entry that can only ever fail.
            assertTrue(Boolean.TRUE.equals(isHttps.invoke(null, url)), "preset URL is HTTPS: " + url);
            assertTrue(url.endsWith(".apk"), "preset URL points at an APK: " + url);
            assertTrue(labels.contains(label), "every build is offered in the manifest options: " + label);
        }

        // --- recommendation, against the two panels actually tested ---
        Method recommend = webview.getDeclaredMethod("recommend", List.class, int.class);
        recommend.setAccessible(true);

        // NSPanel Pro (px30), Android 8.1. ha-paneld says it caps at 138 —
        // and the panel here is already running 138.0.7204.63, so this is a
        // real check against known-correct hardware, not a made-up case.
        Object px30 = recommend.invoke(null, Arrays.asList("arm64-v8a", "armeabi-v7a", "armeabi"), 27);
        assertTrue(px30 != null, "an Android 8.1 arm64 panel gets a recommendation");
        assertTrue(field(px30, "label").startsWith("LineageOS 138"),
            "Android 8.1 caps at WebView 138, got " + field(px30, "label"));

        // WF2489T (rk3576), Android 14.
        Object rk3576 = recommend.invoke(null, Arrays.asList("arm64-v8a", "armeabi-v7a"), 34);
        assertTrue(rk3576 != null, "a modern arm64 panel gets a recommendation");
        assertTrue(field(rk3576, "label").startsWith("LineageOS 150.0.7871.63 - arm64"),
            "Android 14 arm64 takes the newer 64-bit build, got " + field(rk3576, "label"));

        // TPA10 (rk3566), 32-bit Android 11+ — ha-paneld maps this to Cromite,
        // which is why Cromite is ordered ahead of the 32-bit LineageOS build.
        Object tpa10 = recommend.invoke(null, Arrays.asList("armeabi-v7a", "armeabi"), 30);
        assertTrue(tpa10 != null && field(tpa10, "label").startsWith("Cromite"),
            "32-bit Android 11+ takes Cromite per ha-paneld's mapping");

        // The primary ABI decides, not merely what the panel can run: a
        // 64-bit panel lists armeabi-v7a too, and must not be handed a
        // 32-bit WebView.
        assertTrue(field(recommend.invoke(null, Arrays.asList("arm64-v8a", "armeabi-v7a"), 30), "abi")
            .equals("arm64-v8a"), "a 64-bit panel is never recommended a 32-bit build");

        assertTrue(recommend.invoke(null, Arrays.asList("x86_64"), 30) == null,
            "an ABI with no catalogued build yields no recommendation rather than a wrong one");
        assertTrue(recommend.invoke(null, Arrays.asList("arm64-v8a"), 21) == null,
            "an Android older than anything catalogued yields no recommendation");
        assertTrue(recommend.invoke(null, java.util.Collections.emptyList(), 30) == null,
            "no ABI data (older KS) yields no recommendation rather than a guess");
        assertTrue(recommend.invoke(null, (Object) null, 30) == null, "null ABIs are safe");
        assertTrue(recommend.invoke(null, Arrays.asList("arm64-v8a"), 0) == null,
            "no Android level yields no recommendation");

        Method canInstall = webview.getDeclaredMethod("canInstall", String.class, List.class);
        canInstall.setAccessible(true);
        assertTrue(Boolean.FALSE.equals(canInstall.invoke(null,
            "Cromite 147.0.7727.56 - arm 32-bit, Android 11+ (TPA10)", Arrays.asList("arm64-v8a"))),
            "a 32-bit build is refused on a panel that lists no 32-bit ABI");
        assertTrue(Boolean.TRUE.equals(canInstall.invoke(null,
            "Cromite 147.0.7727.56 - arm 32-bit, Android 11+ (TPA10)",
            Arrays.asList("arm64-v8a", "armeabi-v7a"))),
            "an explicit pick the panel can run is allowed even when not recommended");
        assertTrue(Boolean.TRUE.equals(canInstall.invoke(null, "None", Arrays.asList("arm64-v8a"))),
            "labels naming no build are never refused on ABI grounds");

        // --- merge semantics (toggles + free text) ---
        @SuppressWarnings("unchecked")
        List<String> both = (List<String>) merge.invoke(null,
            Arrays.asList("com.vendor.one", "com.vendor.two"), "com.custom.three");
        assertEquals(Arrays.asList("com.vendor.one", "com.vendor.two", "com.custom.three"), both,
            "toggled entries come first, then custom — additive, not replacing");

        @SuppressWarnings("unchecked")
        List<String> deduped = (List<String>) merge.invoke(null,
            Arrays.asList("com.vendor.one"), "com.vendor.one com.custom.two");
        assertEquals(Arrays.asList("com.vendor.one", "com.custom.two"), deduped,
            "a package in both sources is tamed once, not twice");

        @SuppressWarnings("unchecked")
        List<String> nullsafe = (List<String>) merge.invoke(null, (Object) null, (Object) null);
        assertTrue(nullsafe.isEmpty(), "both sources absent yields an empty list, not a crash");

        @SuppressWarnings("unchecked")
        List<String> guarded = (List<String>) merge.invoke(null,
            Arrays.asList("com.android.systemui", "com.vendor.ok"), "android");
        assertEquals(Arrays.asList("com.vendor.ok"), guarded,
            "critical packages are filtered out of the toggle side too, not just typed input");

        @SuppressWarnings("unchecked")
        List<String> invalid = (List<String>) merge.invoke(null,
            Arrays.asList("not a package name!", "com.vendor.ok"), "");
        assertEquals(Arrays.asList("com.vendor.ok"), invalid, "malformed entries are dropped");

        // --- manifest agreement ---
        // Java and the manifest are two hand-maintained copies of the same
        // catalog; a skew means a toggle the panel shows that nothing reads,
        // or a package tamed with no visible control. Check them against
        // each other rather than trusting they stay in step.
        String manifest = new String(Files.readAllBytes(manifestPath()), StandardCharsets.UTF_8);
        for (String key : keys) {
            assertTrue(manifest.contains("\"" + key + "\""), "manifest declares a toggle for " + key);
        }
        for (String pkg : packages) {
            assertTrue(manifest.contains(pkg), "manifest mentions catalog package " + pkg);
        }
        for (String label : labels) {
            assertTrue(manifest.contains(label), "manifest offers WebView option: " + label);
        }
        assertTrue(!manifest.contains("\"tamePreset\""),
            "the replaced preset dropdown is gone from the manifest");
        assertTrue(manifest.contains("\"webviewPreset\""), "manifest declares the WebView dropdown");


        // A catalogue is a fixed list and a panel is not: one panel here runs
        // 153.0.8010.36 while the newest catalogued arm64 build is
        // 150.0.7871.63, so the unguarded recommendation pointed at a
        // downgrade Android refuses anyway.
        Method isNewer = webview.getDeclaredMethod("isNewer", String.class, String.class);
        isNewer.setAccessible(true);
        assertTrue((Boolean) isNewer.invoke(null, "153.0.8010.36", "150.0.7871.63"),
            "a newer milestone is newer");
        assertTrue((Boolean) isNewer.invoke(null, "150.0.7871.63", "150.0.7871.63"),
            "the same build is not worth recommending");
        assertTrue(!(Boolean) isNewer.invoke(null, "138.0.7204.63", "150.0.7871.63"),
            "an older milestone is not newer");
        assertTrue(!(Boolean) isNewer.invoke(null, "138.0.7204.63", "138.0.7204.181"),
            "the patch field is compared numerically, not as text");
        assertTrue(!(Boolean) isNewer.invoke(null, null, "150.0.7871.63"),
            "an unreadable installed version recommends as before");
        assertTrue(!(Boolean) isNewer.invoke(null, "not-a-version", "150.0.7871.63"),
            "an unparseable version recommends as before");

        Method rec3 = webview.getDeclaredMethod("recommend", java.util.List.class, int.class, String.class);
        rec3.setAccessible(true);
        assertTrue(rec3.invoke(null, java.util.Arrays.asList("arm64-v8a"), 34, "153.0.8010.36") == null,
            "a panel past the catalogue is recommended nothing");
        assertTrue(rec3.invoke(null, java.util.Arrays.asList("arm64-v8a"), 34, "149.0.0.1") != null,
            "a panel behind the catalogue still gets its build");

        // A build only upgrades a panel already using its package: the
        // LineageOS builds are com.android.webview and the Google ones
        // com.google.android.webview. Install the wrong one and Android
        // adds a second WebView that is not the provider, changing nothing
        // except 250MB of storage. The NSPanel Pro here runs the LineageOS
        // package, which is why its recommendation must stay on that line.
        Method rec4 = webview.getDeclaredMethod(
            "recommend", java.util.List.class, int.class, String.class, String.class);
        rec4.setAccessible(true);
        Object googlePanel = rec4.invoke(null, Arrays.asList("arm64-v8a"), 34, "150.0.0.1",
            "com.google.android.webview");
        assertTrue(googlePanel != null
            && field(googlePanel, "label").startsWith("Google 153"),
            "a Google-provisioned modern panel is offered the Google build");
        Object aospPanel = rec4.invoke(null, Arrays.asList("arm64-v8a"), 34, "149.0.0.1",
            "com.android.webview");
        assertTrue(aospPanel != null
            && field(aospPanel, "label").startsWith("LineageOS 150"),
            "a LineageOS-provisioned panel stays on the LineageOS line");
        assertTrue(rec4.invoke(null, Arrays.asList("arm64-v8a"), 27, "138.0.7204.63",
            "com.android.webview") == null,
            "the NSPanel Pro is already on the newest build for its package");
        Object unknown = rec4.invoke(null, Arrays.asList("arm64-v8a"), 34, null, null);
        assertTrue(unknown != null && field(unknown, "label").startsWith("LineageOS"),
            "an unknown provider falls back to the catalogue's own convention");
        System.out.println("PASS: tame catalog shape/uniqueness/validation, ethernet exclusion, "
            + "toggle selection semantics, WebView preset URLs and per-panel recommendation "
            + "(px30/rk3576/tpa10, primary-ABI preference, no-guess fallbacks), "
            + "merge ordering/dedup/critical filtering, "
            + "and manifest/Java catalog agreement.");
    }

    private static Path manifestPath() {
        Path here = Paths.get("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            Path candidate = p.resolve("kiosk-satellite-plugin.json");
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("could not locate kiosk-satellite-plugin.json from " + here);
    }

    private static String field(Object row, String name) throws Exception {
        Field f = row.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return String.valueOf(f.get(row));
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!(expected == null ? actual == null : expected.equals(actual))) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }
}
