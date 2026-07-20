package com.cameracheck.driver.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class WsSecurityHeaderTest {

    /**
     * Known vector computed independently (Python hashlib):
     * nonce = bytes 0x00..0x0f, created = "2024-01-02T03:04:05.000Z",
     * password = "secret" -> Base64(SHA1(nonce+created+password)).
     */
    @Test
    void passwordDigestMatchesHandComputedVector() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) i;
        }
        String digest = WsSecurityHeader.passwordDigest(nonce, "2024-01-02T03:04:05.000Z", "secret");
        assertEquals("H+rdeF4qoUw7Wh5v8vvV8ynhaZY=", digest);
    }

    @Test
    void buildProducesSelfConsistentDigest() {
        String xml = WsSecurityHeader.build("admin", "pass123");

        String nonceB64 = extract(xml, "<wsse:Nonce[^>]*>([^<]+)</wsse:Nonce>");
        String created = extract(xml, "<wsu:Created>([^<]+)</wsu:Created>");
        String digest = extract(xml, "<wsse:Password[^>]*>([^<]+)</wsse:Password>");

        assertEquals(16, Base64.getDecoder().decode(nonceB64).length);
        assertTrue(created.endsWith("Z"), "created must be a UTC timestamp");
        assertEquals(WsSecurityHeader.passwordDigest(
                Base64.getDecoder().decode(nonceB64), created, "pass123"), digest);
        assertTrue(xml.contains("<wsse:Username>admin</wsse:Username>"));
        assertTrue(xml.contains("PasswordDigest"), "must declare the PasswordDigest type");
    }

    @Test
    void buildEscapesXmlSpecialsInUsernameAndOmitsHeaderWithoutCredentials() {
        String xml = WsSecurityHeader.build("a<d&min\"", "x");
        assertTrue(xml.contains("<wsse:Username>a&lt;d&amp;min&quot;</wsse:Username>"));
        assertFalse(xml.contains("a<d&min\""));

        assertEquals("", WsSecurityHeader.build(null, "x"));
        assertEquals("", WsSecurityHeader.build("  ", "x"));
    }

    private static String extract(String xml, String regex) {
        Matcher m = Pattern.compile(regex).matcher(xml);
        assertTrue(m.find(), "expected to find " + regex + " in: " + xml);
        return m.group(1);
    }
}
