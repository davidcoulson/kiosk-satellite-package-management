# Changelog

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
