package com.cameracheck.driver.hikvision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Digest Access Authentication (RFC 7616 / RFC 2617, MD5). The JDK
 * HttpClient has no built-in digest support, so this computes the
 * {@code Authorization} header for the retry after a 401 challenge.
 * Supports {@code qop=auth} (with nc/cnonce) and the legacy no-qop form.
 */
final class HttpDigestAuth {

    /** name=value (optionally quoted) pairs in a challenge or credentials list. */
    private static final Pattern PARAM = Pattern.compile(
            "(\\w+)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,\\s]+))");

    private HttpDigestAuth() {
    }

    /** Parses the params of a {@code WWW-Authenticate: Digest ...} challenge (keys lower-cased). */
    static Map<String, String> parseChallenge(String wwwAuthenticate) {
        String s = wwwAuthenticate == null ? "" : wwwAuthenticate.trim();
        if (s.regionMatches(true, 0, "Digest", 0, 6)) {
            s = s.substring(6);
        }
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(s);
        while (m.find()) {
            String value = m.group(2) != null
                    ? m.group(2).replace("\\\"", "\"").replace("\\\\", "\\")
                    : m.group(3);
            params.put(m.group(1).toLowerCase(), value);
        }
        return params;
    }

    /**
     * Builds the {@code Authorization: Digest ...} header value answering the
     * given challenge.
     *
     * @param wwwAuthenticate the WWW-Authenticate header value (Digest scheme)
     * @param method          HTTP method, e.g. "GET"
     * @param uri             request path (+query), e.g. "/ISAPI/System/deviceInfo"
     * @param nonceCount      1-based count of requests made with this server nonce
     * @param cnonce          client nonce (random hex; fixed only in tests)
     */
    static String authorization(String wwwAuthenticate, String method, String uri,
            String username, String password, int nonceCount, String cnonce) {
        Map<String, String> c = parseChallenge(wwwAuthenticate);
        String realm = c.getOrDefault("realm", "");
        String nonce = c.getOrDefault("nonce", "");
        String opaque = c.get("opaque");
        String algorithm = c.get("algorithm");
        String qop = selectQop(c.get("qop"));
        String nc = String.format("%08x", nonceCount);

        String user = username == null ? "" : username;
        String pass = password == null ? "" : password;

        String ha1 = md5Hex(user + ":" + realm + ":" + pass);
        if ("MD5-sess".equalsIgnoreCase(algorithm)) {
            ha1 = md5Hex(ha1 + ":" + nonce + ":" + cnonce);
        }
        String ha2 = md5Hex(method + ":" + uri);
        String response = qop != null
                ? md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2)
                : md5Hex(ha1 + ":" + nonce + ":" + ha2);

        StringBuilder h = new StringBuilder("Digest ");
        h.append("username=\"").append(quote(user)).append('"');
        h.append(", realm=\"").append(quote(realm)).append('"');
        h.append(", nonce=\"").append(quote(nonce)).append('"');
        h.append(", uri=\"").append(quote(uri)).append('"');
        if (qop != null) {
            h.append(", qop=").append(qop);
            h.append(", nc=").append(nc);
            h.append(", cnonce=\"").append(quote(cnonce)).append('"');
        }
        h.append(", response=\"").append(response).append('"');
        if (opaque != null) {
            h.append(", opaque=\"").append(quote(opaque)).append('"');
        }
        if (algorithm != null) {
            h.append(", algorithm=").append(algorithm);
        }
        return h.toString();
    }

    /** Picks "auth" if the server offers it (qop may be a list like "auth,auth-int"). */
    private static String selectQop(String offered) {
        if (offered == null || offered.isBlank()) {
            return null;
        }
        for (String option : offered.split(",")) {
            if ("auth".equalsIgnoreCase(option.trim())) {
                return "auth";
            }
        }
        return null; // only auth-int offered — fall back to the no-qop computation
    }

    static String md5Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String quote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
