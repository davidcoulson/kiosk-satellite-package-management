# Changelog

## 0.5.0

- **The WebView dropdown can now pick for itself.** A new **Recommended for this panel** option reads the panel's primary ABI and Android level from `getDeviceInfo` and resolves to the matching build at install time. Previously you had to know whether the panel was arm64 or arm 32-bit, and which Chromium version its Android release caps at, and get both right before downloading a quarter-gigabyte APK.
- **An impossible pick is refused before downloading.** Choosing a build whose ABI the panel cannot run reports why and stops. An explicit pick the panel *can* run is still honoured even when it isn't the recommendation — overriding on purpose is legitimate; installing something that cannot work is not.
- The plugin subpage now reports the detected panel (board, API level, primary ABI) and which build suits it.
- Requires the `host.read` capability, and Kiosk Satellite **2026.9.42+**, which added these fields in response to [#509](https://github.com/jxlarrea/kiosk-satellite/issues/509). On an older host the fields are absent, the recommendation declines to guess, and manual picks work exactly as before — no root needed either way, since this reads through the host rather than `getprop`.
- Recommendations are tested against the two panels on hand: a px30 on Android 8.1 resolves to LineageOS 138 (which that panel already runs, so the expected answer is known independently) and an rk3576 on Android 14 to LineageOS 150 arm64. A 64-bit panel is never offered a 32-bit build even though it lists `armeabi-v7a` — the *primary* ABI decides.

## 0.4.1

- **Fixes 0.3.0 and 0.4.0 being uninstallable.** The `webviewPreset` description was 488 characters against Kiosk Satellite's 400-character cap for setting descriptions, so the host rejected the entire manifest with "Invalid description" and greyed out **Trust and update**. Both releases were affected; 0.3.0 introduced it. The text is trimmed and the detail it carried lives in the README.
- Added `test/ManifestContractTest.java`, which enforces the SDK 1 manifest contract locally — every length cap, pattern, count limit, and select-option rule mirrored from the host's `PluginManifest.kt`. The build only ever checked that the JSON parsed, which is why a size limit reached a panel. A failing check now names the setting, its actual length, and the limit, instead of surfacing as a disabled button and a three-word error.

## 0.4.0

- **Correction: ha-paneld does maintain a curated package list, and this release ports it.** 0.2.0 and 0.3.0 both claimed it didn't — that was wrong, and the claim shaped the catalog. The list isn't in ha-paneld's documentation; it's in the `provisioning.packages` block of each device profile under `app/src/main/assets/device-profiles/`, with a desired state, importance rating, tags, and a note per package. Fourteen distinct packages across the profiles.
- The catalog is now twelve packages, mostly theirs. New: `com.gulukai.pwmlightdemo` (the only one ha-paneld rates *recommended* — its boot service cycles colours through `/dev/ledjni` and fights the Rockchip LED plugin), `com.rockchip.devicetest`, `com.smartos.xinch.smartiot`, `com.smartos.xinch.smarthome`, `com.smartos.xinch.monitor`.
- Removed `com.smartos.xinch.setting` and `com.smartos.xinch.hardware`: these were mine, guessed from ha-paneld's prose docs, and neither appears in its actual profile data. The real xinch packages are the four above.
- Descriptions now say per package whether it was seen on a panel here, came from an ha-paneld profile, or both, and which profile.
- Deliberately not included, with reasons in the README: `com.android.rockchip.camera2` (qualified even upstream — disabling it breaks camera and HDMI input, and Kiosk Satellite has camera features) and `com.smartos.xinch.communicate` (one model, vendor demo, and the settings budget is finite). Both remain typeable.
- Manifest is at 19 of SDK 1's 20-setting cap. The next package added means one removed.

## 0.3.0

- **Tame is now a list of packages, one toggle each,** replacing 0.2.0's single preset-bundle dropdown. Bundles forced an all-or-nothing pick — you couldn't tame the vendor OTA updater while leaving one factory test app alone — and SDK 1 has no multi-select anywhere in its API, so booleans are the only per-item control available. Nine packages are listed, each with its own provenance note. The free-text field stays and is still additive.
- Migration, if you used a 0.2.0 preset: `tamePreset` is gone and every toggle defaults to off, so the plugin will not re-tame anything on its own — deliberately, since silently re-deriving a bundle selection would tame packages you never individually agreed to. But packages a preset already disabled **stay disabled** across the upgrade: the plugin tracks what it tamed in memory only, so a restart leaves it with nothing to undo. To restore one, switch its toggle on and back off — that runs the `pm enable` / `appops allow` restore explicitly. Every package the bundles covered has a toggle, so nothing is stranded without a control.
- **Known-good System WebView builds in a dropdown.** Panels without Google Play have no WebView update path, and hunting a matching APK is the hard part. The list is ha-paneld's [webview-mirror](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror) release — versions its maintainer verified on real panels and mirrored for durable links — labelled with the ABI and Android version each suits. Unlike the tame catalog, this is a genuine upstream catalog rather than something assembled here. The URL field stays and overrides a pick.
- The uninstall dropdown now lists the same catalog as the tame toggles, so the two stay in step.
- Tests now check the manifest and the Java catalog against each other. They're two hand-maintained copies of one list, and a skew means either a toggle nothing reads or a package tamed with no visible control.

## 0.2.0

- **Dropdowns for tame and uninstall.** A **Tame: preset bundle** select applies a vetted package set in one pick, and an **Uninstall: pick a known package** select lists common vendor/test targets. Both keep their existing text field: the tame preset is *additive* to the typed list (taming is reversible, so combining is safe), while typed text *overrides* the uninstall dropdown (uninstalling isn't reversible, so an explicit target must win). SDK 1's `select` options are fixed in the manifest and can't enumerate installed packages, so these are curated bundles rather than a live app picker.
- Preset entries go through the same validation and critical-package filtering as typed input, so a bundle can't smuggle in something the text field would have refused.
- Bundle provenance is documented per row in the README. Two bundles are verified present on real panels (rk3576 and px30); two come from ha-paneld's hardware notes and are labelled unverified. `com.smartos.xinch.platform.ethernet` is deliberately excluded from every preset — disabling it on a wired panel would strand it.

## 0.1.1-20260911

- Correction: an earlier attempt at this release used a 4-component date-based version (`2026.09.11.01`), which Kiosk Satellite's plugin manifest validator rejects (`FormatException: Invalid plugin ID or version`) — it requires 3-component semver, optionally with a `-suffix`. That broken release has been removed; this one embeds the date as a semver prerelease suffix instead.
- Metadata only otherwise: author field and AI-assisted note in the README.

## 0.1.0

- Install an APK from an HTTPS URL, streamed directly into a root `pm install -S -r -d`, no local file staging.
- Uninstall a package by ID (`pm uninstall`), refusing critical/protected packages.
- Update the System WebView from a plain URL — no per-model catalog, no signature pinning.
- Tame vendor packages: force-stop, disable from relaunching on boot, and deny the overlay permission for a configured list, reversible by removing a package from the list.
- Simulation mode for testing without root, network, or package changes.
