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
    private Boolean lastSimulation;
    // In-memory only — resets to empty on every plugin (re)start, so the
    // full current list is always reasserted at least once per app
    // lifetime. Not a cross-restart regression: re-taming an
    // already-tamed package is a harmless no-op, and re-asserting on
    // every start is deliberate (see the class doc).
    private List<String> previousTameList = Collections.emptyList();

    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        alive.set(true);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "package-management");
            t.setDaemon(true);
            return t;
        });
        configure(settings);
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
                    String url = str(settings.get("webviewUrl"));
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
        rooted = Boolean.TRUE.equals(settings.get("simulation")) || RootShell.isRooted();
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
        if (!rooted) {
            host.status("Root access is required to install packages and isn't available on this panel.", true);
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
            host.status("Root access is required to uninstall packages and isn't available on this panel.", true);
            return;
        }
        boolean ok = RootShell.run("pm uninstall " + pkg, RootShell.COMMAND_TIMEOUT_MS);
        host.status(ok ? "OK: uninstalled " + pkg
            : "Uninstall failed — the package may be a non-removable system app.", !ok);
    }

    /** Applies the tame list's full effect: newly listed packages get
     *  force-stopped, disabled from relaunching, and denied the overlay
     *  permission; packages removed from the list are re-enabled and
     *  re-allowed. One shell script per apply, covering every change. */
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
        return TamePresets.NONE.equals(picked) ? "" : picked;
    }

    private void reconcileTame() {
        List<String> desired = PackageManagementMath.mergeTameSources(
            TamePresets.packagesFor(str(settings.get("tamePreset"))),
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
                host.status("Root access is required to tame vendor packages and isn't available on this panel.", true);
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
                boolean ok = RootShell.run(script.toString(), RootShell.COMMAND_TIMEOUT_MS);
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
        int tamed = previousTameList.size();
        host.status(tamed == 0 ? "Root access available." : "Root access available · " + tamed + " package(s) tamed.", false);
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
    }
}
