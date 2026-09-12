# Changelog

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
