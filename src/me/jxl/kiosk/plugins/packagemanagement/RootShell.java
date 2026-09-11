// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Plain `su -c` command execution — a fresh process per call, same
 *  reasoning as the CPU Performance Mode plugin's identically-named
 *  class: these are rare, user-triggered operations, not a hot path. */
final class RootShell {
    private RootShell() {}

    static final long DETECT_TIMEOUT_MS = 4000L;
    static final long COMMAND_TIMEOUT_MS = 5000L;

    static boolean isRooted() {
        return run("id", DETECT_TIMEOUT_MS);
    }

    static boolean run(String cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String runOutput(String cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            String out = readAll(p.getInputStream());
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Runs [cmd] as root, streaming [input] straight into its stdin (no
     * local file, no Context/cache-directory dependency this plugin has
     * no way to reach) up to [maxBytes]. Returns the process's combined
     * stdout/stderr, or null on a timeout or launch failure — the caller
     * inspects the text for `pm install`'s own "Success"/"Failure [...]"
     * convention. [input] is always closed; [maxBytes] throws rather than
     * silently truncating, since a truncated APK stream must not be
     * treated as a complete one.
     */
    static String runWithStdin(String cmd, InputStream input, long maxBytes, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try {
                    out.append(readAll(p.getInputStream()));
                } catch (Exception ignored) {}
            }, "root-shell-stdout");
            outputReader.setDaemon(true);
            outputReader.start();
            try (OutputStream stdin = p.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                long copied = 0;
                int n;
                while ((n = input.read(buf)) >= 0) {
                    copied += n;
                    if (copied > maxBytes) throw new java.io.IOException("stream exceeded the byte ceiling");
                    stdin.write(buf, 0, n);
                }
            } finally {
                input.close();
            }
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            outputReader.join(1000);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        return out.toString();
    }
}
