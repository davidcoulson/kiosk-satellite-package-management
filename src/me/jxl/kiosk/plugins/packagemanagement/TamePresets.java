// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named bundles of vendor packages, so the common cases are one dropdown
 * choice instead of hand-typing package names into a text field. Pure data
 * plus lookup — no shell, no PluginHost; see test/TamePresetsTest.java.
 *
 * SDK 1's `select` setting takes a fixed option list baked into the
 * manifest, and offers no way to enumerate what's actually installed on
 * the panel, so this can't be a "pick from your installed apps" picker.
 * Bundles are the workaround: one pick expands to a vetted set, and the
 * free-text **Tamed vendor packages** field stays as the escape hatch for
 * anything not covered here. A preset is ADDITIVE to that field, never a
 * replacement — see PackageManagementMath.mergeTameSources.
 *
 * <h2>On how these were chosen</h2>
 *
 * These are NOT ported from a vetted upstream list — ha-paneld has no
 * curated tame list, only a handful of package names mentioned in
 * per-device hardware docs. Each entry below records where it came from:
 *
 * <ul>
 *   <li><b>Verified present</b> on a real panel over adb during authoring.</li>
 *   <li><b>Documented</b> in ha-paneld's hardware notes but not verified
 *       here — the label says so, because an unverified name on the wrong
 *       panel model is a package that simply doesn't exist (harmless) or,
 *       worse, a different vendor's package that matters.</li>
 * </ul>
 *
 * Deliberately excluded: {@code com.smartos.xinch.platform.ethernet}.
 * ha-paneld's docs list it alongside the other xinch vendor packages, but
 * disabling the ethernet platform service on a wired panel would take its
 * network down — and a wall-mounted panel that loses networking is a
 * physical-access recovery job. Nothing that can strand a panel goes in a
 * one-click preset. Anyone who genuinely wants it can still type it into
 * the text field, where the deliberateness is the safeguard.
 */
final class TamePresets {
    private TamePresets() {}

    /** Manifest-facing label for "apply nothing", and the default. */
    static final String NONE = "None";

    // Ordered so the dropdown reads most-specific to most-general.
    private static final Map<String, List<String>> PRESETS = new LinkedHashMap<>();
    static {
        // Verified present on a WF2489T (rk3576) panel over adb.
        // The OTA updater is the one entry ha-paneld documents plainly as a
        // safe, reversible disable: left running it can re-enable ADB and
        // push vendor firmware under you. commonkeepalive is the vendor's
        // process-resurrector, which is what undoes taming anything else.
        PRESETS.put("elclcd panels: OTA updater + keepalive", Arrays.asList(
            "com.elclcd.otaupdater",
            "com.elclcd.commonkeepalive"));

        // Verified present across both panels tested (rk3576 and px30).
        // Factory/QA leftovers with no runtime role on a deployed panel.
        PRESETS.put("Factory test apps (verified on test panels)", Arrays.asList(
            "com.DeviceTest",
            "com.elc.smt_test",
            "com.cghs.stresstest",
            "com.smatek.test"));

        // From ha-paneld's hardware docs; NOT verified on any panel here.
        // platform.ethernet is deliberately absent — see the class doc.
        PRESETS.put("SmartOS/xinch vendor apps (documented, unverified)", Arrays.asList(
            "com.smartos.xinch.setting",
            "com.smartos.xinch.hardware"));

        // From ha-paneld's hardware docs; NOT verified on any panel here.
        PRESETS.put("Tuya test app (documented, unverified)", Collections.singletonList(
            "com.tuya.devicetest"));
    }

    /** Dropdown options for the manifest, {@link #NONE} first. */
    static List<String> optionLabels() {
        List<String> labels = new java.util.ArrayList<>();
        labels.add(NONE);
        labels.addAll(PRESETS.keySet());
        return labels;
    }

    /** The packages a preset label expands to — empty for {@link #NONE},
     *  an unknown label, or null. An unrecognised label resolves to empty
     *  rather than throwing: a manifest/code version skew should quietly
     *  tame nothing, never tame something unintended. */
    static List<String> packagesFor(String label) {
        if (label == null) return Collections.emptyList();
        List<String> packages = PRESETS.get(label);
        return packages == null ? Collections.emptyList() : packages;
    }
}
