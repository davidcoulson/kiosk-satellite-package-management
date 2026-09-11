// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.packagemanagement;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads an APK over HTTPS and installs it as root, streaming the
 * download's bytes straight into `pm install -S <size> -r -d`'s stdin —
 * no local file, since this plugin has no Context (KioskPlugin.start only
 * hands a PluginHost, and PluginHost exposes no general scratch
 * directory) and therefore nowhere sanctioned to stage one.
 *
 * Deliberately does NOT carry ha-paneld's own signature-pinning or
 * database-compatibility machinery: those exist there to protect ITS OWN
 * auto-update chain against a compromised/spoofed release. This plugin
 * only ever installs a URL its own user chose and typed in themselves —
 * there is no separate trust boundary to defend, the same reasoning that
 * makes a plain "pm install" from an admin-chosen APK safe in the first
 * place. `-d` (allow downgrade) is deliberate, matching ha-paneld's own
 * reasoning: a stable↔pre-release channel switch should be able to move
 * either direction.
 */
final class ApkInstaller {
    private ApkInstaller() {}

    static final long MAX_APK_BYTES = 512L * 1024L * 1024L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    private static final long READ_TIMEOUT_MS = 60_000L;
    private static final long INSTALL_TIMEOUT_MS = 10L * 60_000L;
    private static final int MAX_REDIRECTS = 5;

    /** Result of an [install] attempt. */
    static final class Result {
        final boolean ok;
        final String message;
        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /** Downloads [url] (following up to 5 HTTPS-only redirects) and
     *  installs it as root. [pkgHint] is used only in status messages. */
    static Result install(String url, String pkgHint) {
        if (!PackageManagementMath.isHttpsUrl(url)) {
            return new Result(false, "refused: the URL must be HTTPS");
        }
        HttpURLConnection conn = null;
        try {
            URL current = new URL(url);
            for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
                conn = (HttpURLConnection) current.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
                conn.setReadTimeout((int) READ_TIMEOUT_MS);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) return new Result(false, "download failed: redirect with no Location");
                    URL next = PackageManagementMath.httpsRedirect(current, loc);
                    if (next == null) return new Result(false, "refused: a redirect left HTTPS");
                    current = next;
                    continue;
                }
                if (code != 200) {
                    conn.disconnect();
                    return new Result(false, "download failed: HTTP " + code);
                }
                break;
            }
            if (conn == null) return new Result(false, "download failed: too many redirects");
            long declaredLength = conn.getContentLengthLong();
            if (declaredLength <= 0) {
                conn.disconnect();
                return new Result(false, "refused: server did not declare a content length (needed for a streaming install)");
            }
            if (declaredLength > MAX_APK_BYTES) {
                conn.disconnect();
                return new Result(false, "refused: download too large (" + (declaredLength / 1048576) + " MB)");
            }
            InputStream in = conn.getInputStream();
            String cmd = "pm install -S " + declaredLength + " -r -d 2>&1";
            String out = RootShell.runWithStdin(cmd, in, declaredLength, INSTALL_TIMEOUT_MS);
            conn.disconnect();
            if (out == null) return new Result(false, "install timed out or the root shell failed to launch");
            String trimmed = out.trim();
            if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("success")) {
                return new Result(true, "OK: installed" + (pkgHint != null ? " " + pkgHint : ""));
            }
            return new Result(false, "install failed: " + firstLine(trimmed, 200));
        } catch (Exception e) {
            if (conn != null) conn.disconnect();
            return new Result(false, "download failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String firstLine(String s, int max) {
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > max ? line.substring(0, max) : line;
    }
}
