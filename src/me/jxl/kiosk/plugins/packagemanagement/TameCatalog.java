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
 * <h2>Where this list comes from</h2>
 *
 * Mostly ported from ha-paneld, which does maintain a vetted per-package
 * list — not in its docs, but in the {@code provisioning.packages} block
 * of each device profile under {@code app/src/main/assets/device-profiles}.
 * Every entry there carries a {@code desired_state}, an {@code importance}
 * rating, tags, and a note explaining what the package is. Fourteen
 * distinct packages across the profiles; twelve are here.
 *
 * Two of theirs are deliberately left out:
 *
 * <ul>
 *   <li>{@code com.android.rockchip.camera2} — ha-paneld qualifies this one
 *       ("safe to disable unless you use the camera or HDMI input"), and
 *       Kiosk Satellite has real camera features, so a one-tap toggle that
 *       silently breaks them is exactly the kind of thing that doesn't
 *       belong here.</li>
 *   <li>{@code com.smartos.xinch.communicate} — a vendor demo on one panel
 *       model, and the settings budget is finite. Type it if you want it.</li>
 * </ul>
 *
 * Also not a tame candidate, despite appearing in ha-paneld's tpa10
 * hardware doc: {@code com.smartos.xinch.platform.ethernet}. That doc
 * lists it as the panel's *wired networking feature*, not as something to
 * disable — and disabling it on a wired panel would take its network down,
 * which on a wall-mounted panel is a physical-access recovery job. It is
 * named here only so nobody reads that doc and assumes the omission was an
 * oversight.
 *
 * One entry ({@code com.smatek.test}) is ours rather than theirs: verified
 * present on both panels tested here, absent from their profiles. Each
 * description says whether the package was seen on a panel here, came from
 * an ha-paneld profile, or both — because an unverified name on the wrong
 * model is simply a package that doesn't exist, and the toggle does
 * nothing rather than something surprising.
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
        // --- Verified on panels here AND listed by ha-paneld ---
        new Entry("tameOtaUpdater", "com.elclcd.otaupdater",
            "elclcd OTA updater",
            "ELC \"Firmware Upgrade\", the vendor OTA updater. Disable to stop the panel auto-applying vendor firmware that re-adds bloat — and, on some images, re-enables ADB. Verified on rk3576; ha-paneld disables it on smt1019 and wf1589t."),
        new Entry("tameKeepalive", "com.elclcd.commonkeepalive",
            "elclcd keepalive service",
            "ELC \"CommonKeepAlive\", the vendor's process-resurrector — this is what undoes taming anything else, so tame it alongside whatever else you tame on these panels. Verified on rk3576; ha-paneld disables it on smt1019."),
        new Entry("tamePwmLightDemo", "com.gulukai.pwmlightdemo",
            "Vendor RGB LED demo (PwmLightDemo)",
            "The only package ha-paneld rates *recommended* rather than optional. Its boot-started foreground service continuously cycles colours through /dev/ledjni, which fights any LED control of your own. Tame this if you use the Rockchip LED plugin."),
        new Entry("tameDeviceTest", "com.DeviceTest",
            "Factory test: DeviceTest",
            "Generic factory device-test app, in /odm persist so it survives a factory reset. Diagnostic only. Verified on rk3576 and px30; ha-paneld disables it on smt1019 and wf1589t."),
        new Entry("tameStressTest", "com.cghs.stresstest",
            "Factory test: burn-in Stresstest",
            "Factory hardware stress-test (\"burn-in\") tool, a priv-app. Production-line QA with no role on a deployed panel. Verified on rk3576 and px30; ha-paneld disables it on wf1589t."),
        new Entry("tameSmtTest", "com.elc.smt_test",
            "Factory test: elc smt_test",
            "ELC factory QA tool, in /odm persist so it survives a factory reset. Verified on rk3576 and px30; ha-paneld disables it on smt1019."),

        // --- From ha-paneld's device profiles; not seen on a panel here ---
        new Entry("tameRockchipTest", "com.rockchip.devicetest",
            "Factory test: Rockchip devicetest",
            "Rockchip SoC factory device-test suite. Diagnostic only. From ha-paneld's wf1589t profile; not present on the panels tested here, in which case this toggle does nothing."),
        new Entry("tameSmatekTest", "com.smatek.test",
            "Factory test: smatek test",
            "Factory/QA leftover. Verified present on rk3576 and px30 panels here; this one is not in ha-paneld's profiles."),
        new Entry("tameTuyaTest", "com.tuya.devicetest",
            "Factory test: Tuya devicetest",
            "Tuya factory device-test app, in /odm so it persists across a factory reset. Diagnostic only. From ha-paneld's tpa10 profile."),
        new Entry("tameXinchSmartIot", "com.smartos.xinch.smartiot",
            "Xinch SmartIoT control app",
            "SmartOS/Tuya IoT control app — redundant alongside Home Assistant. From ha-paneld's tpa10 profile."),
        new Entry("tameXinchSmartHome", "com.smartos.xinch.smarthome",
            "Xinch smart-home control app",
            "SmartOS/Tuya smart-home control app — redundant on a Home Assistant panel. From ha-paneld's tpa10 profile."),
        new Entry("tameXinchMonitor", "com.smartos.xinch.monitor",
            "Xinch PerformanceMonitor",
            "Vendor diagnostic overlay app. Not needed in normal operation, and its overlay is the kind that lands on top of a dashboard. From ha-paneld's tpa10 profile.")));

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
