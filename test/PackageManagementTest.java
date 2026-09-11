// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;

/** Device-free tests for PackageManagementMath's package-private static
 *  methods, reflected into since this test lives outside the plugin's
 *  package (same convention as the Hello World template's own test). */
public final class PackageManagementTest {
    public static void main(String[] args) throws Exception {
        Class<?> math = Class.forName("me.jxl.kiosk.plugins.packagemanagement.PackageManagementMath");

        Method isValidPkg = math.getDeclaredMethod("isValidPackageName", String.class);
        isValidPkg.setAccessible(true);
        assertTrue((Boolean) isValidPkg.invoke(null, "com.example.app"), "ordinary package name accepted");
        assertTrue((Boolean) isValidPkg.invoke(null, "android"), "bare single-segment package name accepted");
        assertFalse((Boolean) isValidPkg.invoke(null, "com.example.app; rm -rf /"), "shell metacharacters rejected");
        assertFalse((Boolean) isValidPkg.invoke(null, "1com.example"), "segment starting with a digit rejected");
        assertFalse((Boolean) isValidPkg.invoke(null, "com..example"), "empty segment rejected");
        assertFalse((Boolean) isValidPkg.invoke(null, (Object) null), "null rejected");
        assertFalse((Boolean) isValidPkg.invoke(null, ""), "empty string rejected");
        StringBuilder tooLong = new StringBuilder("a");
        for (int i = 0; i < 256; i++) tooLong.append("a");
        assertFalse((Boolean) isValidPkg.invoke(null, tooLong.toString()), "over-length name rejected");

        Method isHttps = math.getDeclaredMethod("isHttpsUrl", String.class);
        isHttps.setAccessible(true);
        assertTrue((Boolean) isHttps.invoke(null, "https://example.com/app.apk"), "https URL accepted");
        assertFalse((Boolean) isHttps.invoke(null, "http://example.com/app.apk"), "plain http rejected");
        assertFalse((Boolean) isHttps.invoke(null, "javascript:alert(1)"), "non-http(s) scheme rejected");
        assertFalse((Boolean) isHttps.invoke(null, (Object) null), "null rejected");
        assertFalse((Boolean) isHttps.invoke(null, ""), "empty string rejected");

        Method redirect = math.getDeclaredMethod("httpsRedirect", URL.class, String.class);
        redirect.setAccessible(true);
        // Compared as strings, not via URL#equals — that method can trigger a
        // real DNS lookup to compare hosts, which has no place in an offline
        // unit test.
        URL base = new URL("https://example.com/release/download");
        assertEquals("https://cdn.example.com/app.apk",
            String.valueOf(redirect.invoke(null, base, "https://cdn.example.com/app.apk")), "absolute https redirect resolves");
        assertEquals("https://example.com/release/app.apk",
            String.valueOf(redirect.invoke(null, base, "app.apk")), "relative redirect resolves against the base");
        assertNull(redirect.invoke(null, base, "http://example.com/app.apk"), "downgrade to http is refused");

        Method isCritical = math.getDeclaredMethod("isCritical", String.class);
        isCritical.setAccessible(true);
        assertTrue((Boolean) isCritical.invoke(null, "android"), "core Android is protected");
        assertTrue((Boolean) isCritical.invoke(null, "com.android.systemui"), "system UI is protected");
        assertTrue((Boolean) isCritical.invoke(null, "me.jxl.kiosk_satellite"), "the host app itself is protected");
        assertFalse((Boolean) isCritical.invoke(null, "com.example.vendorapp"), "an ordinary vendor package is not protected");

        Method parseTame = math.getDeclaredMethod("parseTameList", String.class);
        parseTame.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> parsed = (List<String>) parseTame.invoke(null, "com.a.app,com.b.app com.a.app android com.c.app;bad");
        assertEquals(2, parsed.size(), "critical entries and invalid tokens are dropped, duplicates removed: " + parsed);
        assertTrue(parsed.contains("com.a.app"), "valid entry retained");
        assertTrue(parsed.contains("com.b.app"), "valid entry retained");
        assertFalse(parsed.contains("android"), "critical entry dropped");
        @SuppressWarnings("unchecked")
        List<String> empty = (List<String>) parseTame.invoke(null, (Object) null);
        assertTrue(empty.isEmpty(), "null input parses to an empty list");

        System.out.println("PASS: package name validation, HTTPS/redirect safety, critical-package protection, tame-list parsing.");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("expected true: " + message);
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError("expected false: " + message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!objectsEquals(expected, actual)) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }

    private static void assertNull(Object actual, String message) {
        if (actual != null) throw new AssertionError(message + " — expected null but got " + actual);
    }

    private static boolean objectsEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
