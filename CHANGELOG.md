# Changelog

## 0.1.0

- Install an APK from an HTTPS URL, streamed directly into a root `pm install -S -r -d`, no local file staging.
- Uninstall a package by ID (`pm uninstall`), refusing critical/protected packages.
- Update the System WebView from a plain URL — no per-model catalog, no signature pinning.
- Tame vendor packages: force-stop, disable from relaunching on boot, and deny the overlay permission for a configured list, reversible by removing a package from the list.
- Simulation mode for testing without root, network, or package changes.
