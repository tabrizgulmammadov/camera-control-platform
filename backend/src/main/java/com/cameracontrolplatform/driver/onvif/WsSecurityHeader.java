package com.cameracontrolplatform.driver.onvif;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * WS-Security UsernameToken with Password Digest, as required by ONVIF:
 *   PasswordDigest = Base64( SHA-1( nonce + created + password ) )
 * where nonce is raw random bytes and created is the UTC timestamp string.
 */
final class WsSecurityHeader {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter CREATED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private WsSecurityHeader() {
    }

    /** Builds the {@code <wsse:Security>} XML fragment, or "" when no credentials. */
    static String build(String username, String password) {
        if (username == null || username.isBlank()) {
            return "";
        }
        String pwd = password == null ? "" : password;

        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String created = ZonedDateTime.now(ZoneOffset.UTC).format(CREATED_FORMAT);

        String digest = passwordDigest(nonce, created, pwd);
        String nonceB64 = Base64.getEncoder().encodeToString(nonce);

        return """
                <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" \
                xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">\
                <wsse:UsernameToken>\
                <wsse:Username>%s</wsse:Username>\
                <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">%s</wsse:Password>\
                <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">%s</wsse:Nonce>\
                <wsu:Created>%s</wsu:Created>\
                </wsse:UsernameToken>\
                </wsse:Security>"""
                .formatted(xmlEscape(username), digest, nonceB64, created);
    }

    /** PasswordDigest = Base64( SHA-1( nonce + created + password ) ). */
    static String passwordDigest(byte[] nonce, String created, String password) {
        byte[] createdBytes = created.getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

        byte[] toDigest = new byte[nonce.length + createdBytes.length + passwordBytes.length];
        System.arraycopy(nonce, 0, toDigest, 0, nonce.length);
        System.arraycopy(createdBytes, 0, toDigest, nonce.length, createdBytes.length);
        System.arraycopy(passwordBytes, 0, toDigest, nonce.length + createdBytes.length, passwordBytes.length);

        return Base64.getEncoder().encodeToString(sha1(toDigest));
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
