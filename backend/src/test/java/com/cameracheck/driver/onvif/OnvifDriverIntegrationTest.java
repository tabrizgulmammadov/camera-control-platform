package com.cameracheck.driver.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cameracheck.domain.CameraConnection;
import com.cameracheck.domain.CameraEndpoint;
import com.cameracheck.domain.CameraException;
import com.cameracheck.domain.DeviceInformation;
import com.cameracheck.domain.ErrorCode;
import com.cameracheck.domain.MediaProfile;
import com.cameracheck.domain.StreamType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Full driver flow (connect -> device info -> profiles -> stream URIs)
 * against a canned in-process ONVIF device on an ephemeral port.
 */
class OnvifDriverIntegrationTest {

    private HttpServer server;
    private int port;
    private final List<String> deviceRequests = new CopyOnWriteArrayList<>();
    private final List<String> mediaRequests = new CopyOnWriteArrayList<>();

    private final OnvifCameraDriver driver = new OnvifCameraDriver();

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

    private void installHappyPathDevice() {
        String mediaXAddr = "http://192.168.99.99:80/onvif/Media"; // internal IP on purpose (NAT case)
        server.createContext("/onvif/device_service", exchange -> {
            String body = readBody(exchange);
            deviceRequests.add(body);
            if (body.contains("GetDeviceInformation")) {
                respond(exchange, 200, OnvifSoapFixtures.DEVICE_INFO_HIKVISION);
            } else if (body.contains("GetCapabilities")) {
                respond(exchange, 200, OnvifSoapFixtures.capabilities(mediaXAddr));
            } else {
                respond(exchange, 400, OnvifSoapFixtures.FAULT_NOT_AUTHORIZED);
            }
        });
        // The advertised XAddr path — the driver must pin host/port to ours.
        server.createContext("/onvif/Media", exchange -> {
            String body = readBody(exchange);
            mediaRequests.add(body);
            if (body.contains("GetProfiles")) {
                respond(exchange, 200, OnvifSoapFixtures.PROFILES_HIKVISION);
            } else if (body.contains("GetStreamUri")) {
                String channel = body.contains("Profile_1") ? "101" : body.contains("Profile_2") ? "102" : "103";
                respond(exchange, 200, OnvifSoapFixtures.streamUri(
                        "rtsp://192.168.1.64:554/Streaming/Channels/" + channel));
            } else {
                respond(exchange, 400, "unexpected");
            }
        });
    }

    private CameraEndpoint endpoint() {
        return new CameraEndpoint("127.0.0.1", port, "admin", "pass123");
    }

    @Test
    void fullFlowYieldsCompleteDto() {
        installHappyPathDevice();
        try (CameraConnection conn = driver.connect(endpoint())) {
            DeviceInformation info = conn.getDeviceInformation();
            assertEquals("HIKVISION", info.manufacturer());
            assertEquals("DS-2CD2143G2-I", info.model());

            List<MediaProfile> profiles = conn.getProfiles();
            assertEquals(3, profiles.size());

            MediaProfile main = profiles.get(0);
            assertEquals("Profile_1", main.token());
            assertEquals(StreamType.MAIN, main.streamType());
            assertEquals("H264", main.videoEncoder().encoding());
            assertEquals(1920, main.videoEncoder().resolution().width());
            assertEquals("rtsp://192.168.1.64:554/Streaming/Channels/101", main.rtspUri());

            assertEquals("rtsp://192.168.1.64:554/Streaming/Channels/102", profiles.get(1).rtspUri());
            assertEquals(StreamType.SUB, profiles.get(1).streamType());
            assertEquals(StreamType.OTHER, profiles.get(2).streamType());
        }

        // Every request must carry a WSSE UsernameToken with a password digest.
        assertTrue(deviceRequests.size() >= 2, "expected GetDeviceInformation + GetCapabilities");
        assertEquals(4, mediaRequests.size(), "expected GetProfiles + 3x GetStreamUri");
        for (String req : deviceRequests) {
            assertWsseDigest(req);
        }
        for (String req : mediaRequests) {
            assertWsseDigest(req);
        }
    }

    private static void assertWsseDigest(String soapRequest) {
        assertTrue(soapRequest.contains("UsernameToken"), "missing WSSE UsernameToken: " + soapRequest);
        assertTrue(soapRequest.contains("<wsse:Username>admin</wsse:Username>"), "missing username");
        assertTrue(soapRequest.contains("PasswordDigest"), "password must be digest, not plain text");
        assertTrue(!soapRequest.contains("pass123"), "plain-text password must never be sent");
        assertTrue(soapRequest.contains("<wsse:Nonce"), "missing nonce");
        assertTrue(soapRequest.contains("<wsu:Created>"), "missing created timestamp");
    }

    @Test
    void http404MapsToOnvifNotEnabled() {
        server.createContext("/onvif/device_service",
                exchange -> respond(exchange, 404, "Not Found"));
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    @Test
    void nonSoapReplyMapsToOnvifNotEnabled() {
        server.createContext("/onvif/device_service",
                exchange -> respond(exchange, 200, "<html><body>Web admin UI</body></html>"));
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    @Test
    void connectionRefusedMapsToOnvifNotEnabled() {
        server.stop(0); // nothing listens on the port any more
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    @Test
    void notAuthorizedFaultMapsToAuthFailed() {
        server.createContext("/onvif/device_service",
                exchange -> respond(exchange, 400, OnvifSoapFixtures.FAULT_NOT_AUTHORIZED));
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_AUTH_FAILED, e.code());
    }

    @Test
    void http401MapsToAuthFailed() {
        server.createContext("/onvif/device_service",
                exchange -> respond(exchange, 401, "Unauthorized"));
        CameraException e = assertThrows(CameraException.class,
                () -> driver.connect(endpoint()).getDeviceInformation());
        assertEquals(ErrorCode.ONVIF_AUTH_FAILED, e.code());
    }

    // ---- plumbing ----------------------------------------------------------

    private static String readBody(HttpExchange exchange) throws IOException {
        try (var in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/soap+xml; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
