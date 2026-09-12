// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Device-free tests for the tame preset bundles and the preset+custom
 *  merge, reflected into since these live outside the plugin's package. */
public final class TamePresetsTest {
    public static void main(String[] args) throws Exception {
        Class<?> presets = Class.forName("me.jxl.kiosk.plugins.packagemanagement.TamePresets");
        Class<?> math = Class.forName("me.jxl.kiosk.plugins.packagemanagement.PackageManagementMath");

        Method optionLabels = presets.getDeclaredMethod("optionLabels");
        optionLabels.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) optionLabels.invoke(null);
        assertEquals("None", labels.get(0), "None is first so it reads as the default");
        assertTrue(labels.size() >= 2, "there is at least one real bundle");
        assertTrue(labels.size() <= 32, "SDK 1 caps a select at 32 options");
        for (String l : labels) {
            assertTrue(!l.isEmpty() && l.length() <= 80, "each option fits SDK 1's 1..80 char limit: " + l);
        }
        assertEquals(labels.size(), new java.util.LinkedHashSet<>(labels).size(), "no duplicate option labels");

        Method packagesFor = presets.getDeclaredMethod("packagesFor", String.class);
        packagesFor.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> none = (List<String>) packagesFor.invoke(null, "None");
        assertTrue(none.isEmpty(), "None expands to nothing");
        @SuppressWarnings("unchecked")
        List<String> unknown = (List<String>) packagesFor.invoke(null, "Not A Real Preset");
        assertTrue(unknown.isEmpty(), "an unknown label tames nothing rather than throwing");
        @SuppressWarnings("unchecked")
        List<String> nullLabel = (List<String>) packagesFor.invoke(null, (Object) null);
        assertTrue(nullLabel.isEmpty(), "null tames nothing");

        @SuppressWarnings("unchecked")
        List<String> elclcd = (List<String>) packagesFor.invoke(null, "elclcd panels: OTA updater + keepalive");
        assertTrue(elclcd.contains("com.elclcd.otaupdater"), "the verified OTA updater is in its bundle");

        // The network-critical package is excluded from every bundle: taming
        // it on a wired panel would strand it. See TamePresets' class doc.
        for (String label : labels) {
            @SuppressWarnings("unchecked")
            List<String> pkgs = (List<String>) packagesFor.invoke(null, label);
            assertTrue(!pkgs.contains("com.smartos.xinch.platform.ethernet"),
                "no preset may contain the ethernet platform package: " + label);
        }

        Method merge = math.getDeclaredMethod("mergeTameSources", List.class, String.class);
        merge.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> both = (List<String>) merge.invoke(null,
            Arrays.asList("com.vendor.one", "com.vendor.two"), "com.custom.three");
        assertEquals(Arrays.asList("com.vendor.one", "com.vendor.two", "com.custom.three"), both,
            "preset entries come first, then custom — additive, not replacing");

        @SuppressWarnings("unchecked")
        List<String> deduped = (List<String>) merge.invoke(null,
            Arrays.asList("com.vendor.one"), "com.vendor.one com.custom.two");
        assertEquals(Arrays.asList("com.vendor.one", "com.custom.two"), deduped,
            "a package in both sources is tamed once, not twice");

        @SuppressWarnings("unchecked")
        List<String> presetOnly = (List<String>) merge.invoke(null, Arrays.asList("com.vendor.one"), "");
        assertEquals(Arrays.asList("com.vendor.one"), presetOnly, "empty custom text keeps the preset");

        @SuppressWarnings("unchecked")
        List<String> customOnly = (List<String>) merge.invoke(null, java.util.Collections.emptyList(), "com.custom.one");
        assertEquals(Arrays.asList("com.custom.one"), customOnly, "no preset keeps the custom list");

        @SuppressWarnings("unchecked")
        List<String> nullsafe = (List<String>) merge.invoke(null, (Object) null, (Object) null);
        assertTrue(nullsafe.isEmpty(), "both sources absent yields an empty list, not a crash");

        // A preset must not be a way around the critical-package guard.
        @SuppressWarnings("unchecked")
        List<String> guarded = (List<String>) merge.invoke(null,
            Arrays.asList("com.android.systemui", "com.vendor.ok"), "android");
        assertEquals(Arrays.asList("com.vendor.ok"), guarded,
            "critical packages are filtered out of the preset side too, not just typed input");

        @SuppressWarnings("unchecked")
        List<String> invalid = (List<String>) merge.invoke(null,
            Arrays.asList("not a package name!", "com.vendor.ok"), "");
        assertEquals(Arrays.asList("com.vendor.ok"), invalid, "malformed preset entries are dropped");

        System.out.println("PASS: preset bundle shape and SDK option limits, unknown-label safety, "
            + "ethernet exclusion, preset+custom merge ordering/dedup/null-safety, critical filtering on both sources.");
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
