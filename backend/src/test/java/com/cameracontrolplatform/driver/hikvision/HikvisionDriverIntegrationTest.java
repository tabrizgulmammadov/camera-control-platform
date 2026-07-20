package com.cameracontrolplatform.driver.hikvision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cameracontrolplatform.domain.CameraConnection;
import com.cameracontrolplatform.domain.CameraEndpoint;
import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.DeviceInformation;
import com.cameracontrolplatform.domain.ErrorCode;
import com.cameracontrolplatform.domain.MediaProfile;
import com.cameracontrolplatform.domain.StreamType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Full Hikvision driver flow against a canned in-process ISAPI device that
 * enforces HTTP Digest authentication (challenge, then RFC 2617 verification
 * of the client's response), plus the contract error mappings.
 */
class HikvisionDriverIntegrationTest {

    private static final String USER = "admin";
    private static final String PASS = "pass123";
    private static final String REALM = "IP Camera(C6789)";
    private static final String NONCE = "5d41402abc4b2a76b9719d911017c592";

    private HttpServer server;
    private int port;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    private final HikvisionCameraDriver driver = new HikvisionCameraDriver();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CameraEndpoint endpoint() {
        return new CameraEndpoint("127.0.0.1", port, USER, PASS);
    }

    /** Installs digest-protected ISAPI resources. */
    private void installDigestDevice() {
        server.createContext("/ISAPI/System/deviceInfo",
                exchange -> digestProtected(exchange, IsapiFixtures.DEVICE_INFO));
        server.createContext("/ISAPI/Streaming/channels",
                exchange -> digestProtected(exchange, IsapiFixtures.STREAMING_CHANNELS));
    }

    private void digestProtected(HttpExchange exchange, String body) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            authHeaders.add(authorization);
        }
        if (authorization == null || !verifyDigest(authorization, exchange.getRequestURI().getPath())) {
            exchange.getResponseHeaders().set("WWW-Authenticate",
                    "Digest qop=\"auth\", realm=\"" + REALM + "\", nonce=\"" + NONCE + "\"");
            respond(exchange, 401, "Unauthorized", "text/html");
            return;
        }
        respond(exchange, 200, body, "application/xml");
    }

    /** Recomputes the RFC 2617 qop=auth response server-side and compares. */
    private static boolean verifyDigest(String authorization, String path) {
        if (!authorization.startsWith("Digest ")) {
            return false;
        }
        Map<String, String> p = HttpDigestAuth.parseChallenge(authorization);
        if (!USER.equals(p.get("username")) || !REALM.equals(p.get("realm"))
                || !NONCE.equals(p.get("nonce")) || !path.equals(p.get("uri"))
                || !"auth".equals(p.get("qop"))) {
            return false;
        }
        String ha1 = HttpDigestAuth.md5Hex(USER + ":" + REALM + ":" + PASS);
        String ha2 = HttpDigestAuth.md5Hex("GET:" + path);
        String expected = HttpDigestAuth.md5Hex(ha1 + ":" + NONCE + ":" + p.get("nc")
                + ":" + p.get("cnonce") + ":auth:" + ha2);
        return expected.equals(p.get("response"));
    }

    @Test
    void fullFlowWithDigestAuthYieldsProfiles() {
        installDigestDevice();
        try (CameraConnection conn = driver.connect(endpoint())) {
            DeviceInformation info = conn.getDeviceInformation();
            assertEquals("Hikvision", info.manufacturer());
            assertEquals("DS-2CD2143G2-I", info.model());
            assertEquals("V5.7.3", info.firmwareVersion());

            List<MediaProfile> profiles = conn.getProfiles();
            assertEquals(3, profiles.size());
            MediaProfile main = profiles.get(0);
            assertEquals(StreamType.MAIN, main.streamType());
            assertEquals(25, main.videoEncoder().frameRate());
            // RTSP host must be the endpoint the user supplied.
            assertEquals("rtsp://127.0.0.1:554/Streaming/Channels/101", main.rtspUri());
            assertEquals(StreamType.SUB, profiles.get(1).streamType());
        }

        // Both resources were fetched via the 401 -> digest retry flow.
        assertEquals(2, authHeaders.size(), "one authenticated retry per resource");
        for (String header : authHeaders) {
            assertTrue(header.startsWith("Digest "), header);
            assertTrue(!header.contains(PASS), "plain-text password must never be sent");
            assertNotNull(HttpDigestAuth.parseChallenge(header).get("cnonce"));
        }
    }

    @Test
    void wrongPasswordMapsToAuthFailed() {
        installDigestDevice();
        CameraConnection conn = driver.connect(
                new CameraEndpoint("127.0.0.1", port, USER, "wrong-password"));
        CameraException e = assertThrows(CameraException.class, conn::getDeviceInformation);
        assertEquals(ErrorCode.ONVIF_AUTH_FAILED, e.code());
    }

    @Test
    void basicOnlyCameraFallsBackToBasicAuth() {
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
        server.createContext("/ISAPI/System/deviceInfo", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!expectedBasic.equals(authorization)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"" + REALM + "\"");
                respond(exchange, 401, "Unauthorized", "text/html");
                return;
            }
            respond(exchange, 200, IsapiFixtures.DEVICE_INFO, "application/xml");
        });

        try (CameraConnection conn = driver.connect(endpoint())) {
            assertEquals("DS-2CD2143G2-I", conn.getDeviceInformation().model());
        }
    }

    @Test
    void nonXmlReplyMapsToNotEnabled() {
        server.createContext("/ISAPI/System/deviceInfo",
                exchange -> respond(exchange, 200, IsapiFixtures.NON_XML_LOGIN_PAGE, "text/html"));
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    @Test
    void http404MapsToNotEnabled() {
        server.createContext("/ISAPI/System/deviceInfo",
                exchange -> respond(exchange, 404, "Not Found", "text/plain"));
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    @Test
    void connectionRefusedMapsToNotEnabled() {
        server.stop(0); // nothing listens on the port any more
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    // ---- plumbing ----------------------------------------------------------

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
