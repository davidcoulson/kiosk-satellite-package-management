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
| System WebView APK (URL) | An HTTPS URL to a `com.android.webview` build for this panel's ABI. Trigger **Update System WebView**. There is no per-model catalog here — you choose the build; nothing is auto-selected or version-checked for you. |
| Uninstall: pick a known package | Dropdown of common vendor/test packages, so the usual targets don't have to be typed. The text field below overrides it when non-empty. |
| Tame: preset bundle | Dropdown of one-click bundles (see below). Applied **in addition to** the text list, not instead of it. |
| Tamed vendor packages | A space or comma separated list of package IDs, added to whatever the preset selects. **Applied automatically** whenever either changes (and reasserted once at every app start) — no separate action to trigger. See below. |
| Simulation mode | Exercises every control without touching hardware or packages. |

## Why no signature pinning or version catalogs

This is a deliberate difference from ha-paneld's own installer, which pins a signer certificate and maintains a per-device-model "known-good WebView" catalog to protect *its own* auto-update chain against a compromised or spoofed release. This plugin has no such chain to protect: it only ever installs a URL its own administrator chose and typed in — the same trust boundary as running `pm install` from an adb shell yourself. Adding a signature pin or a version catalog here would just be complexity with nothing behind it to defend.

## Preset bundles, and where they came from

The **Tame: preset bundle** dropdown turns the common cases into one pick. SDK 1's `select` takes a fixed option list baked into the manifest and offers no way to enumerate what's installed on the panel, so this can't be a "choose from your apps" picker — bundles are the workaround, with the text field as the escape hatch.

| Bundle | Packages | Provenance |
| --- | --- | --- |
| elclcd panels: OTA updater + keepalive | `com.elclcd.otaupdater`, `com.elclcd.commonkeepalive` | **Verified present** on a WF2489T (rk3576) over adb. The OTA updater is the one case ha-paneld documents plainly as a safe, reversible disable — left running it can re-enable ADB and push vendor firmware under you. `commonkeepalive` is the vendor's process-resurrector, which is what undoes taming anything else. |
| Factory test apps | `com.DeviceTest`, `com.elc.smt_test`, `com.cghs.stresstest`, `com.smatek.test` | **Verified present** across both an rk3576 and a px30 panel. Factory/QA leftovers with no runtime role on a deployed panel. |
| SmartOS/xinch vendor apps | `com.smartos.xinch.setting`, `com.smartos.xinch.hardware` | **Documented, unverified** — from ha-paneld's hardware notes, not confirmed on any panel here. |
| Tuya test app | `com.tuya.devicetest` | **Documented, unverified** — same caveat. |

These are **not** ported from a vetted upstream list. ha-paneld has no curated tame list; it has a handful of package names mentioned in per-device hardware docs, plus one roadmap note about a runaway Zigbee guard (a shell script, not a package). Everything above was assembled for this plugin, and each row says how much confidence it carries. A package name that doesn't exist on your model is simply skipped, so an unverified entry on the wrong hardware is inert rather than harmful.

**`com.smartos.xinch.platform.ethernet` is deliberately excluded**, even though ha-paneld's docs list it alongside the other xinch packages. Disabling the ethernet platform service on a wired panel takes its network down, and a wall-mounted panel that loses networking is a physical-access recovery job. Nothing that can strand a panel belongs one click away. It can still be typed into the text field, where the deliberateness is the safeguard.

Presets are additive and reversible: clearing the dropdown re-enables whatever it disabled, exactly as removing a name from the text list does.

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

`tools/test.py` runs device-free unit tests: package-name validation, HTTPS/redirect safety, critical-package protection, tame-list parsing, and the preset bundles (option limits, unknown-label safety, the ethernet exclusion, and that preset+custom merging dedupes and still filters critical packages on both sides). The download-and-stream-install mechanism itself (`pm install -S <size> -r -d`, fed the APK bytes directly over `su`'s stdin with no local file — this plugin has no Context and therefore no sanctioned scratch directory to stage a download in) was verified against a real HTTPS GitHub release asset piped through the exact same command shape on real hardware; that verification isn't automated here since it needs a device and a network.

## Publishing and handoff

Apache-2.0. The plugin ID is `package-management`. See [jxlarrea/kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the SDK 1 documentation this plugin was built against.

Author: David Coulson. Built with AI assistance (Claude Code).
