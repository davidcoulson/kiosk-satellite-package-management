# Changelog

## 0.1.1-20260911

- Correction: an earlier attempt at this release used a 4-component date-based version (`2026.09.11.01`), which Kiosk Satellite's plugin manifest validator rejects (`FormatException: Invalid plugin ID or version`) — it requires 3-component semver, optionally with a `-suffix`. That broken release has been removed; this one embeds the date as a semver prerelease suffix instead.
- Metadata only otherwise: author field and AI-assisted note in the README.

## 0.1.0

- Install an APK from an HTTPS URL, streamed directly into a root `pm install -S -r -d`, no local file staging.
- Uninstall a package by ID (`pm uninstall`), refusing critical/protected packages.
- Update the System WebView from a plain URL — no per-model catalog, no signature pinning.
- Tame vendor packages: force-stop, disable from relaunching on boot, and deny the overlay permission for a configured list, reversible by removing a package from the list.
- Simulation mode for testing without root, network, or package changes.
