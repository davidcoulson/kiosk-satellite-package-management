// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Install/uninstall an APK by URL, update the System WebView, and tame
 * intrusive vendor packages — all root shell commands (`pm install`,
 * `pm uninstall`, `pm disable-user`, `am force-stop`, `appops set`), no
 * native code and no KS entities/hardware access.
 *
 * Install/uninstall/WebView update are one-shot actions triggered by their
 * commands, reading whichever URL/package the settings currently hold —
 * they never fire from {@link #configure} itself, so an unrelated setting
 * change (e.g. editing the tame list) can never silently re-trigger a
 * stale pending install. Tame reconciliation is the opposite: it runs on
 * every {@link #configure}, same as ha-paneld's own TameController, so a
 * firmware update that quietly re-enables a tamed vendor app gets
 * corrected again on the next settings write or app restart.
 */
public final class PackageManagementPlugin implements KioskPlugin {
    private final AtomicBoolean alive = new AtomicBoolean();
    private PluginHost host;
    private ExecutorService worker;
    private Map<String, Object> settings = new HashMap<>();

    private volatile boolean rooted;
    // Panel hardware, read from getDeviceInfo (KS 2026.9.42+, issue #509).
    // Empty until the first read answers, and on a host too old to carry
    // these fields -- every use degrades to "no recommendation" rather
    // than guessing, so an older KS keeps working with manual picks.
    private volatile List<String> abis = Collections.emptyList();
    private volatile int sdkInt;
    private volatile String hardwareLabel = "";
    private Boolean lastSimulation;
    // In-memory only — resets to empty on every plugin (re)start, so the
    // full current list is always reasserted at least once per app
    // lifetime. Not a cross-restart regression: re-taming an
    // already-tamed package is a harmless no-op, and re-asserting on
    // every start is deliberate (see the class doc).
    private List<String> previousTameList = Collections.emptyList();

    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        PrivilegedShell.attach(host);
        alive.set(true);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "package-management");
            t.setDaemon(true);
            return t;
        });
        readHardware();
        configure(settings);
    }

    /** Caches the panel's ABIs and Android level. Fire-and-forget with the
     *  result folded back onto the worker, the same shape the network
     *  plugin uses -- blocking the worker on a host callback would deadlock
     *  if the host answers on that thread. */
    private void readHardware() {
        host.executeCommand("getDeviceInfo", Collections.emptyMap(), (ok, data, error) -> submit(() -> {
            if (!ok || !(data instanceof Map)) return;
            Map<?, ?> info = (Map<?, ?>) data;
            Object rawAbis = info.get("abis");
            if (rawAbis instanceof List) {
                List<String> parsed = new ArrayList<>();
                for (Object abi : (List<?>) rawAbis) {
                    if (abi != null) parsed.add(String.valueOf(abi));
                }
                abis = Collections.unmodifiableList(parsed);
            }
            Object level = info.get("sdkInt");
            if (level instanceof Number) sdkInt = ((Number) level).intValue();
            String board = str(info.get("board"));
            String device = str(info.get("device"));
            hardwareLabel = (!board.isEmpty() ? board : device)
                + (sdkInt > 0 ? " / API " + sdkInt : "")
                + (abis.isEmpty() ? "" : " / " + abis.get(0));
            reportStatus();
        }));
    }

    public void configure(Map<String, Object> values) {
        Map<String, Object> copy = new HashMap<>(values);
        submit(() -> {
            boolean simulation = Boolean.TRUE.equals(copy.get("simulation"));
            boolean recheck = lastSimulation == null || lastSimulation != simulation;
            settings = copy;
            lastSimulation = simulation;
            if (recheck) detect();
            reconcileTame();
        });
    }

    public void execute(String command, Map<String, Object> args) {
        submit(() -> {
            switch (command) {
                case "detect":
                    detect();
                    reportStatus();
                    break;
                case "installFromUrl": {
                    String url = str(settings.get("installUrl"));
                    runInstall(url, null);
                    break;
                }
                case "uninstallPackage": {
                    runUninstall(effectiveUninstallTarget());
                    break;
                }
                case "updateWebView": {
                    String url = effectiveWebViewUrl();
                    runInstall(url, "System WebView");
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown command");
            }
        });
    }

    public void onEvent(String event, Map<String, Object> payload) {
        // No window, no light — nothing to observe.
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private void detect() {
        PrivilegedShell.detect();
        rooted = Boolean.TRUE.equals(settings.get("simulation")) || PrivilegedShell.available();
    }

    /** Installing needs genuine root, not merely a privileged channel:
     *  the APK is streamed over stdin and Shizuku offers no stdin. See
     *  PrivilegedShell's class doc. */
    private boolean canInstall() {
        return Boolean.TRUE.equals(settings.get("simulation"))
            || PrivilegedShell.MODE_ROOT.equals(PrivilegedShell.mode())
            || PrivilegedShell.shizukuIsRoot();
    }

    private boolean simulation() {
        return Boolean.TRUE.equals(settings.get("simulation"));
    }

    private void runInstall(String url, String pkgHint) {
        if (url.isEmpty()) {
            host.status("No URL configured.", true);
            return;
        }
        if (!PackageManagementMath.isHttpsUrl(url)) {
            host.status("Refused: the URL must be HTTPS.", true);
            return;
        }
        if (simulation()) {
            host.status("Simulation mode — would install from " + url, false);
            return;
        }
        if (!canInstall()) {
            host.status(PrivilegedShell.available()
                ? "Installing needs root. This panel has " + PrivilegedShell.mode()
                    + ", which can tame and uninstall packages but cannot stream an APK to the installer."
                : "Root access is required to install packages and isn't available on this panel.", true);
            return;
        }
        host.status("Downloading" + (pkgHint != null ? " " + pkgHint : "") + "…", false);
        ApkInstaller.Result result = ApkInstaller.install(url, pkgHint);
        host.status(result.message, !result.ok);
    }

    private void runUninstall(String pkg) {
        if (pkg.isEmpty()) {
            host.status("No package configured.", true);
            return;
        }
        if (!PackageManagementMath.isValidPackageName(pkg)) {
            host.status("Refused: not a valid package name.", true);
            return;
        }
        if (PackageManagementMath.isCritical(pkg)) {
            host.status("Refused: " + pkg + " is a protected package.", true);
            return;
        }
        if (simulation()) {
            host.status("Simulation mode — would uninstall " + pkg, false);
            return;
        }
        if (!rooted) {
            host.status("Root or Shizuku access is required to uninstall packages and isn't available on this panel.", true);
            return;
        }
        boolean ok = PrivilegedShell.run("pm uninstall " + pkg, RootShell.COMMAND_TIMEOUT_MS);
        host.status(ok ? "OK: uninstalled " + pkg
            : "Uninstall failed — the package may be a non-removable system app.", !ok);
    }

    /** The package **Uninstall package** acts on: the typed one when the
     *  text field holds anything, otherwise the dropdown's pick. Text wins
     *  deliberately — uninstall is destructive and one-shot, so if someone
     *  has typed a specific target, that is unambiguously what they meant;
     *  silently uninstalling a dropdown leftover instead would be the worst
     *  possible surprise. (Tame merges its two sources instead of choosing,
     *  because taming is reversible and additive — see mergeTameSources.) */
    private String effectiveUninstallTarget() {
        String typed = str(settings.get("uninstallPackage"));
        if (!typed.isEmpty()) return typed;
        String picked = str(settings.get("uninstallPreset"));
        return TameCatalog.NONE.equals(picked) ? "" : picked;
    }

    /** The WebView APK to install: the typed URL when the field holds
     *  anything, otherwise the URL the dropdown pick maps to. Text wins for
     *  the same reason it does on uninstall — an explicitly typed target is
     *  unambiguous, and a leftover dropdown pick silently overriding it
     *  would replace the component that renders the dashboard. */
    private String effectiveWebViewUrl() {
        String typed = str(settings.get("webviewUrl"));
        if (!typed.isEmpty()) return typed;

        String picked = str(settings.get("webviewPreset"));
        if (WebViewPresets.AUTO.equals(picked)) {
            WebViewPresets.Build match = WebViewPresets.recommend(abis, sdkInt);
            if (match == null) {
                host.status(abis.isEmpty()
                    ? "Can't recommend a WebView build: this Kiosk Satellite build doesn't report panel hardware. Pick one manually."
                    : "No catalogued WebView build suits this panel (" + hardwareLabel + "). Pick one manually or use the URL field.", true);
                return "";
            }
            host.status("Installing the build for this panel: " + match.label, false);
            return match.url;
        }

        // An explicit pick overrides the recommendation on purpose, so only
        // an install that cannot succeed is worth blocking.
        if (!WebViewPresets.canInstall(picked, abis) && !abis.isEmpty()) {
            host.status("Refused: " + WebViewPresets.abiFor(picked) + " does not run on this panel ("
                + abis.get(0) + "). That build cannot install here.", true);
            return "";
        }
        return WebViewPresets.urlFor(picked);
    }

    /** Applies the tame list's full effect: newly listed packages get
     *  force-stopped, disabled from relaunching, and denied the overlay
     *  permission; packages removed from the list are re-enabled and
     *  re-allowed. One shell script per apply, covering every change. */
    private void reconcileTame() {
        List<String> desired = PackageManagementMath.mergeTameSources(
            TameCatalog.selectedPackages(settings),
            (String) settings.getOrDefault("tameVendorPackages", ""));
        List<String> toTame = new ArrayList<>(desired);
        toTame.removeAll(previousTameList);
        List<String> toUntame = new ArrayList<>(previousTameList);
        toUntame.removeAll(desired);

        if (!toTame.isEmpty() || !toUntame.isEmpty()) {
            if (simulation()) {
                host.status("Simulation mode — would tame " + toTame.size()
                    + " and restore " + toUntame.size() + " package(s).", false);
            } else if (!rooted) {
                host.status("Root or Shizuku access is required to tame vendor packages and isn't available on this panel.", true);
            } else {
                StringBuilder script = new StringBuilder();
                for (String pkg : toTame) {
                    script.append("am force-stop ").append(pkg).append(" 2>/dev/null; ")
                        .append("pm disable-user --user 0 ").append(pkg).append(" 2>/dev/null; ")
                        .append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW deny 2>/dev/null\n");
                }
                for (String pkg : toUntame) {
                    script.append("pm enable ").append(pkg).append(" 2>/dev/null; ")
                        .append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW allow 2>/dev/null\n");
                }
                boolean ok = PrivilegedShell.run(script.toString(), RootShell.COMMAND_TIMEOUT_MS);
                if (!ok) host.status("One or more tame operations failed.", true);
            }
        }
        previousTameList = desired;
        if (toTame.isEmpty() && toUntame.isEmpty()) reportStatus();
    }

    private void reportStatus() {
        if (!alive.get()) return;
        if (simulation()) {
            host.status("Simulation mode. No hardware or packages are being changed.", false);
            return;
        }
        if (!rooted) {
            host.status("Root access (e.g. via Magisk) is required for this plugin's features.", true);
            return;
        }
        StringBuilder line = new StringBuilder(
            PrivilegedShell.MODE_SHIZUKU.equals(PrivilegedShell.mode())
                ? "Shizuku access available" + (PrivilegedShell.shizukuIsRoot() ? " (root)." : " (shell) — taming and uninstalling work; installing needs root.")
                : "Root access available.");
        int tamed = previousTameList.size();
        if (tamed > 0) line.append(" ").append(tamed).append(" package(s) tamed.");
        if (!hardwareLabel.isEmpty()) {
            line.append("\nPanel: ").append(hardwareLabel).append(".");
            String installed = installedWebView();
            line.append("\nWebView: ").append(installed == null
                ? "could not read the installed build." : installed);
            WebViewPresets.Build match = WebViewPresets.recommend(abis, sdkInt);
            if (match != null) {
                line.append("\nCatalogued build for this panel: ").append(match.label).append(".");
            }
        }
        host.status(line.toString(), false);
    }

    /**
     * What the panel is actually running: the WebView provider package and
     * its version, read from the system rather than inferred.
     *
     * This line used to print the *catalogued* build's label under a
     * "WebView:" heading, which reads as the installed one and is not. On a
     * panel running 152.0.7977.88 it reported "LineageOS 150.0.7871.63",
     * because that was the newest build in the catalogue for that ABI --
     * and it would have reported the same after an update that silently
     * failed, which is the worse half of the bug.
     *
     * The provider is whatever {@code webview_provider} names, since a
     * panel can be switched between providers; the two stock package names
     * are the fallback when the setting is unset, and the name goes through
     * the same validator as every other package this plugin shells out
     * with.
     */
    private String installedWebView() {
        String provider = PrivilegedShell.runOutput(
            "settings get global webview_provider", RootShell.COMMAND_TIMEOUT_MS);
        if (provider != null) provider = provider.trim();
        // Same validator every other package name in this plugin goes
        // through before it reaches a shell.
        if (provider == null || provider.isEmpty() || "null".equals(provider)
            || !PackageManagementMath.isValidPackageName(provider)) {
            provider = null;
        }
        String[] candidates = provider != null
            ? new String[]{provider}
            : new String[]{"com.google.android.webview", "com.android.webview"};
        for (String pkg : candidates) {
            String out = PrivilegedShell.runOutput(
                "dumpsys package " + pkg + " 2>/dev/null | grep -m1 versionName",
                RootShell.COMMAND_TIMEOUT_MS);
            String version = WebViewPresets.parseVersionName(out);
            if (version != null) return version + " (" + pkg + ")";
        }
        return null;
    }

    private interface Task { void run() throws Exception; }

    private void submit(Task task) {
        if (!alive.get()) return;
        worker.execute(() -> {
            if (!alive.get()) return;
            try {
                task.run();
            } catch (Exception e) {
                host.status(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), true);
            }
        });
    }

    public void stop() throws Exception {
        alive.set(false);
        worker.shutdownNow();
        worker.awaitTermination(1000, TimeUnit.MILLISECONDS);
        // Untame every currently-tamed package on disable/uninstall — the
        // same "leave no lasting effect behind" convention as the LED
        // plugin turning the light off and the CPU plugin restoring Auto.
        if (rooted && !simulation() && !previousTameList.isEmpty()) {
            StringBuilder script = new StringBuilder();
            for (String pkg : previousTameList) {
                script.append("pm enable ").append(pkg).append(" 2>/dev/null; ")
                    .append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW allow 2>/dev/null\n");
            }
            RootShell.run(script.toString(), RootShell.COMMAND_TIMEOUT_MS);
        }
            // Last, so anything above still has a shell to run in: ends
        // the persistent root session rather than leaving a root
        // shell alive for a plugin that is no longer running.
        PrivilegedShell.detach();
}
}
