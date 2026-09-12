// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Runs this plugin's two privileged reads through whichever channel the
 * panel actually has: a root shell, or Shizuku, or neither.
 *
 * <h2>What Shizuku can and cannot do here</h2>
 *
 * Taming and uninstalling are exactly what adb debloating is, and all of
 * it — {@code pm disable-user}, {@code pm enable}, {@code pm uninstall},
 * {@code am force-stop}, {@code appops set} — runs as Android's shell user
 * (UID 2000). Verified against a real panel with root deliberately unused.
 * So on an unrooted panel with Shizuku, the whole vendor-taming half of
 * this plugin works, which is the half most people want.
 *
 * <b>Installing does not.</b> {@link ApkInstaller} streams APK bytes into
 * {@code pm install -S <size>} over stdin, and the host's Shizuku call
 * takes an executable plus arguments with no stdin channel at all. Staging
 * the download to a file first does not rescue it: the obvious shared
 * location, {@code /data/local/tmp}, is owned by shell and this plugin
 * runs as Kiosk Satellite's own UID, which cannot write there. Install and
 * the WebView update that builds on it therefore stay root-only, and say
 * so rather than failing obscurely.
 *
 * <h2>Order, and why root comes first</h2>
 *
 * Root wins when both are available: the persistent session in
 * {@link RootShell} costs one grant for the plugin's lifetime and then a
 * write-and-read per command, whereas every Shizuku call is a fresh
 * round trip through a binder to a helper process. Shizuku is the fallback
 * for panels that have no root, not a replacement for it.
 *
 * <h2>Shell syntax still works</h2>
 *
 * {@code executeShizuku} takes an absolute executable and an argument
 * array, with no shell interpretation — but {@code /system/bin/sh} is an
 * absolute executable, so passing {@code sh -c <script>} keeps pipes,
 * redirection and {@code 2>/dev/null} working exactly as they do under
 * root. What changes between the two channels is the permissions the
 * commands run with, never the syntax they are written in.
 *
 * <h2>Blocking on an async call</h2>
 *
 * The host answers {@code executeShizuku} on its own worker thread, not
 * the caller's, so waiting on a latch here blocks only this plugin's
 * worker and cannot deadlock against the host. The wait is bounded past
 * the command's own timeout so a host that never answers still returns
 * control instead of pinning the thread.
 */
final class PrivilegedShell {
    private PrivilegedShell() {}

    /** How privileged commands are currently reaching the system. */
    static final String MODE_NONE = "none";
    static final String MODE_ROOT = "root";
    static final String MODE_SHIZUKU = "Shizuku";

    private static volatile PluginHost host;
    private static volatile String mode = MODE_NONE;
    private static volatile int shizukuUid = -1;

    static void attach(PluginHost pluginHost) {
        host = pluginHost;
    }

    static void detach() {
        RootShell.shutdown();
        host = null;
        mode = MODE_NONE;
        shizukuUid = -1;
    }

    /** Re-checks which channel is usable. Root first; Shizuku only when
     *  root is absent. Cheap enough to call from the plugin's detect
     *  path, which is the only place that needs a fresh answer — a panel
     *  does not gain root while the app is running, but Shizuku genuinely
     *  can be started or authorized mid-session. */
    static String detect() {
        if (RootShell.isRooted()) {
            mode = MODE_ROOT;
            return mode;
        }
        if (shizukuReady()) {
            mode = MODE_SHIZUKU;
            return mode;
        }
        mode = MODE_NONE;
        return mode;
    }

    static String mode() {
        return mode;
    }

    /** True when Shizuku is the active channel and its helper runs as
     *  root rather than shell. Worth distinguishing in status text: a
     *  Shizuku started from a root shell has no privilege gap at all,
     *  which explains why something works on one panel and not another. */
    static boolean shizukuIsRoot() {
        return MODE_SHIZUKU.equals(mode) && shizukuUid == 0;
    }

    static boolean available() {
        return !MODE_NONE.equals(mode);
    }

    private static boolean shizukuReady() {
        PluginHost current = host;
        if (current == null) return false;
        try {
            Map<String, Object> state = current.shizukuState();
            if (state == null || !Boolean.TRUE.equals(state.get("granted"))) return false;
            Object uid = state.get("uid");
            shizukuUid = uid instanceof Number ? ((Number) uid).intValue() : -1;
            return true;
        } catch (Throwable ignored) {
            // An older host without the Shizuku capability throws rather
            // than answering; that is simply "no Shizuku here".
            return false;
        }
    }

    /** Runs [cmd] and returns trimmed stdout, or null on any failure. */
    static String runOutput(String cmd, long timeoutMs) {
        if (MODE_ROOT.equals(mode)) return RootShell.runOutput(cmd, timeoutMs);
        if (MODE_SHIZUKU.equals(mode)) return shizukuOutput(cmd, timeoutMs);
        return null;
    }

    /** Runs [cmd], returning whether it exited 0 within [timeoutMs]. */
    static boolean run(String cmd, long timeoutMs) {
        if (MODE_ROOT.equals(mode)) return RootShell.run(cmd, timeoutMs);
        if (MODE_SHIZUKU.equals(mode)) return shizukuOutput(cmd, timeoutMs) != null;
        return false;
    }

    private static String shizukuOutput(String cmd, long timeoutMs) {
        PluginHost current = host;
        if (current == null) return null;
        final AtomicReference<String> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            current.executeShizuku(
                new String[]{"/system/bin/sh", "-c", cmd},
                (int) Math.max(1L, Math.min(timeoutMs, Integer.MAX_VALUE)),
                (ok, data, error) -> {
                    try {
                        if (ok && data instanceof Map) {
                            Map<?, ?> value = (Map<?, ?>) data;
                            Object exit = value.get("exitCode");
                            boolean timedOut = Boolean.TRUE.equals(value.get("timedOut"));
                            boolean succeeded = !timedOut
                                && exit instanceof Number && ((Number) exit).intValue() == 0;
                            if (succeeded) {
                                Object out = value.get("stdout");
                                result.set(out == null ? "" : String.valueOf(out).trim());
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
        } catch (Throwable ignored) {
            return null;
        }
        try {
            // Past the command's own timeout, so a host that never calls
            // back releases this thread rather than holding it forever.
            if (!done.await(timeoutMs + 5000L, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }
}
