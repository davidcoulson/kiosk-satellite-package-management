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
        assertTrue(labels.size() >= 2 && labels.size() <= 32, "SDK 1 caps a select at 32 options");
        for (String l : labels) {
            assertTrue(!l.isEmpty() && l.length() <= 80, "each option fits SDK 1's 1..80 char limit: " + l);
        }

        Method urlFor = webview.getDeclaredMethod("urlFor", String.class);
        urlFor.setAccessible(true);
        assertEquals("", urlFor.invoke(null, "None"), "None resolves to no URL");
        assertEquals("", urlFor.invoke(null, "Not A Real Build"),
            "an unknown label installs nothing rather than throwing");
        assertEquals("", urlFor.invoke(null, (Object) null), "null resolves to no URL");

        Method all = webview.getDeclaredMethod("all");
        all.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> builds = (Map<String, String>) all.invoke(null);
        Method isHttps = math.getDeclaredMethod("isHttpsUrl", String.class);
        isHttps.setAccessible(true);
        for (Map.Entry<String, String> e : builds.entrySet()) {
            String url = (String) urlFor.invoke(null, e.getKey());
            assertEquals(e.getValue(), url, "each label resolves to its own URL: " + e.getKey());
            // runInstall refuses non-HTTPS, so a preset that isn't HTTPS
            // would be a dropdown entry that can only ever fail.
            assertTrue(Boolean.TRUE.equals(isHttps.invoke(null, url)), "preset URL is HTTPS: " + url);
            assertTrue(url.endsWith(".apk"), "preset URL points at an APK: " + url);
        }

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

        System.out.println("PASS: tame catalog shape/uniqueness/validation, ethernet exclusion, "
            + "toggle selection semantics, WebView preset URLs, merge ordering/dedup/critical filtering, "
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
