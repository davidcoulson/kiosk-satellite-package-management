// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The catalog of vendor packages worth taming, one per entry, each surfaced
 * as its own on/off setting. Pure data plus lookup — no shell, no
 * PluginHost; see test/TameCatalogTest.java.
 *
 * <h2>Why a list of toggles and not a dropdown</h2>
 *
 * This started as a single `select` of preset bundles, which forced a false
 * choice: a panel that wants the OTA updater tamed but needs one factory
 * test app left alone couldn't say so, because picking a bundle is
 * all-or-nothing and SDK 1's `select` is single-value with no multi-select
 * anywhere in the API. Booleans are the only per-item control SDK 1 offers,
 * so the catalog is flattened to one toggle per package. That also makes
 * the panel self-documenting: every package that can be tamed is visible
 * with its own description, instead of hidden inside a bundle label.
 *
 * The cost is the manifest settings budget — SDK 1 caps a plugin at 20
 * settings, and each package spends one. That is the real ceiling on this
 * list, so a package earns a row only if it is genuinely worth taming on a
 * panel someone would run Kiosk Satellite on. Anything else belongs in the
 * free-text **Tamed vendor packages** field, which stays as the escape
 * hatch and is ADDITIVE to whatever is toggled here — see
 * PackageManagementMath.mergeTameSources.
 *
 * <h2>On how these were chosen</h2>
 *
 * These are NOT ported from a vetted upstream list — ha-paneld has no
 * curated tame list, only a handful of package names mentioned in
 * per-device hardware docs. Each entry records where it came from:
 *
 * <ul>
 *   <li><b>Verified present</b> on a real panel over adb during authoring.</li>
 *   <li><b>Documented</b> in ha-paneld's hardware notes but not verified
 *       here — the description says so, because an unverified name on the
 *       wrong panel model is a package that simply doesn't exist
 *       (harmless) or, worse, a different vendor's package that matters.</li>
 * </ul>
 *
 * Deliberately excluded: {@code com.smartos.xinch.platform.ethernet}.
 * ha-paneld's docs list it alongside the other xinch vendor packages, but
 * disabling the ethernet platform service on a wired panel would take its
 * network down — and a wall-mounted panel that loses networking is a
 * physical-access recovery job. Nothing that can strand a panel gets a
 * one-tap toggle. Anyone who genuinely wants it can still type it into the
 * text field, where the deliberateness is the safeguard.
 */
final class TameCatalog {
    private TameCatalog() {}

    /** Manifest-facing label for "apply nothing" on the uninstall dropdown. */
    static final String NONE = "None";

    /** One tameable package: its manifest setting key, the package it
     *  tames, and the text the panel shows for it. */
    static final class Entry {
        final String settingKey;
        final String pkg;
        final String title;
        final String description;

        Entry(String settingKey, String pkg, String title, String description) {
            this.settingKey = settingKey;
            this.pkg = pkg;
            this.title = title;
            this.description = description;
        }
    }

    // Ordered by how commonly the package actually gets in the way, so the
    // ones most panels want are at the top of the group.
    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
        new Entry("tameOtaUpdater", "com.elclcd.otaupdater",
            "elclcd OTA updater",
            "Verified on rk3576 panels. The one entry ha-paneld documents plainly as a safe, reversible disable — left running it can re-enable ADB and push vendor firmware under you."),
        new Entry("tameKeepalive", "com.elclcd.commonkeepalive",
            "elclcd keepalive service",
            "Verified on rk3576 panels. The vendor's process-resurrector — this is what undoes taming anything else, so tame it alongside whatever else you tame on these panels."),
        new Entry("tameDeviceTest", "com.DeviceTest",
            "Factory test: DeviceTest",
            "Verified on rk3576 and px30 panels. A factory/QA leftover with no runtime role on a deployed panel."),
        new Entry("tameSmtTest", "com.elc.smt_test",
            "Factory test: elc smt_test",
            "Verified on rk3576 and px30 panels. Factory/QA leftover."),
        new Entry("tameStressTest", "com.cghs.stresstest",
            "Factory test: cghs stresstest",
            "Verified on rk3576 and px30 panels. Factory/QA leftover."),
        new Entry("tameSmatekTest", "com.smatek.test",
            "Factory test: smatek test",
            "Verified on rk3576 and px30 panels. Factory/QA leftover."),
        new Entry("tameXinchSetting", "com.smartos.xinch.setting",
            "SmartOS/xinch settings app",
            "Documented in ha-paneld's hardware notes, not verified on a panel here. If it isn't installed on your model, toggling this does nothing."),
        new Entry("tameXinchHardware", "com.smartos.xinch.hardware",
            "SmartOS/xinch hardware service",
            "Documented in ha-paneld's hardware notes, not verified on a panel here. Note the ethernet platform service is deliberately not offered — disabling it on a wired panel would strand it."),
        new Entry("tameTuyaTest", "com.tuya.devicetest",
            "Tuya device test app",
            "Documented in ha-paneld's hardware notes, not verified on a panel here.")));

    /** Every tameable package, in panel display order. */
    static List<Entry> entries() {
        return ENTRIES;
    }

    /** The packages whose toggles are on, in catalog order. A missing or
     *  non-boolean value counts as off, so a settings map from an older
     *  version (or a skewed manifest) tames nothing it wasn't asked to. */
    static List<String> selectedPackages(Map<String, Object> settings) {
        List<String> selected = new ArrayList<>();
        if (settings == null) return selected;
        for (Entry entry : ENTRIES) {
            if (Boolean.TRUE.equals(settings.get(entry.settingKey))) selected.add(entry.pkg);
        }
        return selected;
    }

    /** Package IDs for the uninstall dropdown's options, {@link #NONE}
     *  first — the same catalog, since a package worth taming is the same
     *  one someone might instead choose to remove outright. */
    static List<String> uninstallOptions() {
        List<String> options = new ArrayList<>();
        options.add(NONE);
        for (Entry entry : ENTRIES) options.add(entry.pkg);
        return options;
    }
}
