package com.cameracheck.driver.hikvision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** RFC 2617 §3.5 known-answer vector plus challenge-parsing edge cases. */
class HttpDigestAuthTest {

    /** The canonical example from RFC 2617 (also reused in RFC 7616 for MD5). */
    private static final String RFC_CHALLENGE = "Digest realm=\"testrealm@host.com\", "
            + "qop=\"auth,auth-int\", "
            + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
            + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"";

    @Test
    void rfc2617KnownAnswerVector() {
        String header = HttpDigestAuth.authorization(
                RFC_CHALLENGE, "GET", "/dir/index.html",
                "Mufasa", "Circle Of Life", 1, "0a4f113b");

        // Expected response digest straight from RFC 2617 §3.5.
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""),
                "digest response mismatch: " + header);
        assertTrue(header.startsWith("Digest "));
        assertTrue(header.contains("username=\"Mufasa\""));
        assertTrue(header.contains("realm=\"testrealm@host.com\""));
        assertTrue(header.contains("nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""));
        assertTrue(header.contains("uri=\"/dir/index.html\""));
        assertTrue(header.contains("qop=auth"));
        assertTrue(header.contains("nc=00000001"));
        assertTrue(header.contains("cnonce=\"0a4f113b\""));
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
    }

    @Test
    void nonceCountIsEightDigitHex() {
        String header = HttpDigestAuth.authorization(
                RFC_CHALLENGE, "GET", "/x", "u", "p", 27, "abc");
        assertTrue(header.contains("nc=0000001b"), header);
    }

    @Test
    void legacyChallengeWithoutQopOmitsNcAndCnonce() {
        String challenge = "Digest realm=\"cam\", nonce=\"n1\"";
        String header = HttpDigestAuth.authorization(challenge, "GET", "/ISAPI/System/deviceInfo",
                "admin", "pass", 1, "whatever");

        // response = MD5(HA1:nonce:HA2), self-consistent check
        String ha1 = HttpDigestAuth.md5Hex("admin:cam:pass");
        String ha2 = HttpDigestAuth.md5Hex("GET:/ISAPI/System/deviceInfo");
        String expected = HttpDigestAuth.md5Hex(ha1 + ":n1:" + ha2);
        assertTrue(header.contains("response=\"" + expected + "\""), header);
        assertFalse(header.contains("nc="), "no qop -> no nc: " + header);
        assertFalse(header.contains("cnonce="), "no qop -> no cnonce: " + header);
    }

    @Test
    void parsesQuotedAndUnquotedParams() {
        Map<String, String> params = HttpDigestAuth.parseChallenge(
                "Digest realm=\"IP Camera(C1234)\", nonce=\"abc==\", stale=FALSE, algorithm=MD5, qop=\"auth\"");
        assertEquals("IP Camera(C1234)", params.get("realm"));
        assertEquals("abc==", params.get("nonce"));
        assertEquals("FALSE", params.get("stale"));
        assertEquals("MD5", params.get("algorithm"));
        assertEquals("auth", params.get("qop"));
    }

    @Test
    void qopAuthPickedFromList() {
        String header = HttpDigestAuth.authorization(
                "Digest realm=\"r\", nonce=\"n\", qop=\"auth-int,auth\"",
                "GET", "/x", "u", "p", 1, "c");
        assertTrue(header.contains("qop=auth,") || header.contains("qop=auth "), header);
        assertTrue(header.contains("nc=00000001"), header);
    }
}
