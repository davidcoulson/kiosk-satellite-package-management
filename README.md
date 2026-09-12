# Package Management for Kiosk Satellite

Install or uninstall an APK by URL, update the panel's System WebView, and neutralize intrusive vendor packages — no native code, every action is a root shell command (`pm install`, `pm uninstall`, `pm disable-user`, `am force-stop`, `appops set`).

## Requirements

- Kiosk Satellite with **plugin SDK 1 support**.
- A rooted panel (e.g. Magisk). Every action here needs root — there is no direct-access fallback.

## Install and use

1. Wait for a stable GitHub release and its GitHub Actions build to complete.
2. Open **Plugin Manager > Add plugin**, paste this repository URL, review the manifest and README and choose **Trust and install**.
3. Enable **Package Management** on its entry row and open the subpage.
4. Fill in the field for whichever action you need, then trigger its command below. Settings save automatically; nothing runs until you trigger the matching action.

The plugin declares four actions — **Install APK from URL**, **Uninstall package**, **Update System WebView** and **Check root access**. Assign them in Gestures, or add a kiosk drawer shortcut / Home Assistant button, so a URL or package name can be pre-configured once and re-triggered without reopening the subpage.

## Controls

| Setting | Behavior |
| --- | --- |
| APK to install (URL) | An HTTPS URL to an APK. Trigger **Install APK from URL** to download and install it. |
| Package to uninstall | An exact package ID. Trigger **Uninstall package**. Only removable (non-system, non-critical) packages can actually be removed — Android itself refuses to uninstall a system app this way. |
| System WebView: known-good build | Dropdown of builds verified on real panels (see below). Trigger **Update System WebView** to apply. |
| System WebView APK (URL) | An HTTPS URL to a `com.android.webview` build for this panel's ABI. Overrides the dropdown when non-empty, for anything the catalog doesn't cover. |
| Uninstall: pick a known package | Dropdown of the same vendor/test packages the tame list offers, so the usual targets don't have to be typed. The text field below overrides it when non-empty. |
| Tame: one toggle per package | Each tameable vendor package is its own on/off switch (see below), so you can tame exactly the ones you want. |
| Tamed vendor packages | A space or comma separated list of extra package IDs, added to whatever is toggled on. **Applied automatically** whenever any of it changes (and reasserted once at every app start) — no separate action to trigger. See below. |
| Simulation mode | Exercises every control without touching hardware or packages. |

## Why no signature pinning or version catalogs

This is a deliberate difference from ha-paneld's own installer, which pins a signer certificate and maintains a per-device-model "known-good WebView" catalog to protect *its own* auto-update chain against a compromised or spoofed release. This plugin has no such chain to protect: it only ever installs a URL its own administrator chose and typed in — the same trust boundary as running `pm install` from an adb shell yourself. Adding a signature pin or a version catalog here would just be complexity with nothing behind it to defend.

## The tame catalog, and where it came from

Each package worth taming is its own toggle rather than an entry in a preset bundle. Bundles were the first attempt and forced a false choice: a panel that wants the vendor OTA updater tamed but one factory test app left alone can't say so, because picking a bundle is all-or-nothing and SDK 1's `select` is single-value with no multi-select anywhere in the API. Booleans are the only per-item control SDK 1 offers, so the catalog is flat — one switch per package, each with its own description, and the text field below as the escape hatch for anything not listed.

The ceiling on this list is SDK 1's cap of 20 settings per plugin, each package spending one. A package earns a row only if it is genuinely worth taming on a panel someone would run Kiosk Satellite on.

| Toggle | Package | Provenance |
| --- | --- | --- |
| elclcd OTA updater | `com.elclcd.otaupdater` | **Verified present** on a WF2489T (rk3576) over adb. The one case ha-paneld documents plainly as a safe, reversible disable — left running it can re-enable ADB and push vendor firmware under you. |
| elclcd keepalive service | `com.elclcd.commonkeepalive` | **Verified present** (rk3576). The vendor's process-resurrector, which is what undoes taming anything else. |
| Factory test: DeviceTest | `com.DeviceTest` | **Verified present** on rk3576 and px30. Factory/QA leftover with no runtime role on a deployed panel. |
| Factory test: elc smt_test | `com.elc.smt_test` | **Verified present** on rk3576 and px30. |
| Factory test: cghs stresstest | `com.cghs.stresstest` | **Verified present** on rk3576 and px30. |
| Factory test: smatek test | `com.smatek.test` | **Verified present** on rk3576 and px30. |
| SmartOS/xinch settings app | `com.smartos.xinch.setting` | **Documented, unverified** — from ha-paneld's hardware notes, not confirmed on any panel here. |
| SmartOS/xinch hardware service | `com.smartos.xinch.hardware` | **Documented, unverified** — same caveat. |
| Tuya device test app | `com.tuya.devicetest` | **Documented, unverified** — same caveat. |

These are **not** ported from a vetted upstream list. ha-paneld has no curated tame list; it has a handful of package names mentioned in per-device hardware docs, plus one roadmap note about a runaway Zigbee guard (a shell script, not a package). Everything above was assembled for this plugin, and each row says how much confidence it carries. A package name that doesn't exist on your model is simply skipped, so an unverified toggle on the wrong hardware is inert rather than harmful.

**`com.smartos.xinch.platform.ethernet` is deliberately absent**, even though ha-paneld's docs list it alongside the other xinch packages. Disabling the ethernet platform service on a wired panel takes its network down, and a wall-mounted panel that loses networking is a physical-access recovery job. Nothing that can strand a panel gets a one-tap toggle. It can still be typed into the text field, where the deliberateness is the safeguard.

Toggles are additive and reversible: switching one off re-enables whatever it disabled, exactly as removing a name from the text list does.

## System WebView builds

Panels without Google Play ship years-old WebView and have no update path, which is a large part of why a Home Assistant dashboard feels broken on them. The **System WebView: known-good build** dropdown lists versions ha-paneld's maintainer verified on real panels and mirrored as [GitHub release assets](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror) specifically so they'd have durable links. Unlike the tame catalog, this *is* a real upstream catalog rather than something assembled here; the per-panel mapping in the option labels is theirs.

| Build | Suits |
| --- | --- |
| LineageOS 138.0.7204.63 | arm64, Android 8.1 — NSPanel Pro (px30), which caps at 138 |
| LineageOS 150.0.7871.63 (arm64) | arm64, newer Android |
| LineageOS 150.0.7871.63 (arm 32-bit) | armeabi-v7a, newer Android |
| Cromite 147.0.7727.56 | armeabi-v7a, Android 11+ — TPA10 (rk3566) |

Two things to know before picking one:

- **These are not Google-signed.** They're LineageOS and Cromite *SystemWebView* builds, which deliberately use the `com.android.webview` package so Android selects them as the provider automatically. That's the documented approach for Play-less panels, not a workaround — but it's a different signer than stock, so a panel that still gets WebView through Play should use Play instead.
- **The build must match both the ABI and the Android version.** An NSPanel Pro on Android 8.1 caps at WebView 138; newer builds refuse to install. A mismatch fails the install rather than damaging anything, so a wrong pick is recoverable — but WebView is what renders the dashboard, so verify the panel still loads after changing it.

The URL field remains, and overrides a dropdown pick when non-empty, for a build this list doesn't carry.

## Tame vendor packages

Some firmware updates reintroduce a vendor app that relaunches on boot and draws a floating overlay over the dashboard — ha-paneld's own documented example is a Sonoff NSPanel Pro update that brought back `com.eWeLinkControlPanel`. Listing a package here neutralizes it three ways, all reversible:

1. **Force-stop** it immediately (`am force-stop`).
2. **Disable** it from relaunching on boot (`pm disable-user --user 0` — reversible via `pm enable`).
3. **Deny** its floating-window permission (`appops set … SYSTEM_ALERT_WINDOW deny`).

Removing a package from the list restores it: `pm enable` plus `appops set … SYSTEM_ALERT_WINDOW allow`. This plugin always restores to **allow**, not necessarily whatever the package's permission was before it was ever tamed — a deliberate simplification from ha-paneld's own reconciler, which persists and restores the exact prior mode. In practice this matches every real tame scenario: a vendor app worth taming got there specifically *because* it had that permission and was abusing it, so restoring to allow puts it back exactly where it started.

Core Android (`android`, `com.android.systemui`, `com.android.settings`, `com.android.phone`) and Kiosk Satellite itself (`me.jxl.kiosk_satellite`) can never be listed — both the plugin's own validation and any package list you type are filtered against this the same way before anything is ever applied.

Verified on real hardware: the disable/enable and force-stop round trip, and the `appops` deny/allow round trip against a package that genuinely holds the overlay permission, both confirmed working exactly as coded.

## Build and test

```sh
export JAVA_HOME=/path/to/jdk
python3 tools/test.py
python3 tools/build.py
```

`tools/test.py` runs device-free unit tests: package-name validation, HTTPS/redirect safety, critical-package protection, tame-list parsing, the tame catalog (uniqueness, the ethernet exclusion, that every catalog package survives the same validation a typed one does, and that only genuinely-true toggles count), the WebView presets (option limits, unknown-label safety, every preset URL being HTTPS and pointing at an APK), and that toggle+custom merging dedupes and still filters critical packages on both sides. It also checks the manifest and the Java catalog against each other, since they're two hand-maintained copies of the same list and a skew means either a toggle nothing reads or a package tamed with no visible control. The download-and-stream-install mechanism itself (`pm install -S <size> -r -d`, fed the APK bytes directly over `su`'s stdin with no local file — this plugin has no Context and therefore no sanctioned scratch directory to stage a download in) was verified against a real HTTPS GitHub release asset piped through the exact same command shape on real hardware; that verification isn't automated here since it needs a device and a network.

## Publishing and handoff

Apache-2.0. The plugin ID is `package-management`. See [jxlarrea/kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the SDK 1 documentation this plugin was built against.

Author: David Coulson. Built with AI assistance (Claude Code).
