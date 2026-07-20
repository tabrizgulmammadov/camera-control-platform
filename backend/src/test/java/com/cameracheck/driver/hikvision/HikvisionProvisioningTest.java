package com.cameracheck.driver.hikvision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cameracheck.domain.CameraEndpoint;
import com.cameracheck.domain.OnvifProvisioning;
import com.sun.net.httpserver.HttpServer;

/**
 * Provisioning flow against a mock ISAPI device: the Integrate config must be
 * read, flipped to enable=true and PUT back, and the ONVIF user must be
 * POSTed (new name) or PUT (existing name). Digest auth is covered by
 * {@link HikvisionDriverIntegrationTest}; this server accepts all requests.
 */
class HikvisionProvisioningTest {

    private static final String NS = "http://www.hikvision.com/ver20/XMLSchema";
    private static final String OK = "<?xml version=\"1.0\"?><ResponseStatus xmlns=\"" + NS + "\">"
            + "<statusCode>1</statusCode><statusString>OK</statusString></ResponseStatus>";

    private HttpServer server;
    private final Map<String, String> capturedBodies = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ISAPI/System/Network/Integrate", ex -> {
            if ("GET".equals(ex.getRequestMethod())) {
                respond(ex, 200, "<?xml version=\"1.0\"?><Integrate xmlns=\"" + NS + "\">"
                        + "<ONVIF><enable>false</enable><certificateType>digest/wsse</certificateType></ONVIF>"
                        + "<CGI><enable>true</enable></CGI></Integrate>");
            } else {
                capturedBodies.put("PUT /Integrate", body(ex));
                respond(ex, 200, OK);
            }
        });
        server.createContext("/ISAPI/Security/ONVIF/users", ex -> {
            if ("GET".equals(ex.getRequestMethod())) {
                respond(ex, 200, "<?xml version=\"1.0\"?><UserList xmlns=\"" + NS + "\">"
                        + "<User><id>1</id><userName>existing</userName><userType>administrator</userType></User>"
                        + "</UserList>");
            } else {
                capturedBodies.put(ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), body(ex));
                respond(ex, 200, OK);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HikvisionConnection connection() {
        CameraEndpoint endpoint = new CameraEndpoint(
                "localhost", server.getAddress().getPort(), "admin", "pw");
        return new HikvisionConnection(new IsapiClient(), endpoint);
    }

    @Test
    void enablesIntegrationAndCreatesNewUser() {
        OnvifProvisioning.Result result = connection().provisionOnvif("onvifuser", "OnvifPass1");

        assertEquals("ONVIF integration protocol enabled", result.integrationStatus());
        assertEquals("ONVIF user 'onvifuser' created", result.userStatus());
        assertNotNull(result.note());

        String integrateBody = capturedBodies.get("PUT /Integrate");
        assertNotNull(integrateBody, "Integrate config must be PUT back");
        assertTrue(integrateBody.contains("<enable>true</enable>"),
                "ONVIF enable must be flipped to true, got: " + integrateBody);
        // unrelated settings from the GET must be preserved
        assertTrue(integrateBody.contains("CGI"));

        String userBody = capturedBodies.get("POST /ISAPI/Security/ONVIF/users");
        assertNotNull(userBody, "new user must be POSTed to the collection");
        assertTrue(userBody.contains("<userName>onvifuser</userName>"));
        assertTrue(userBody.contains("<password>OnvifPass1</password>"));
        assertTrue(userBody.contains("<id>2</id>"), "id must be max existing + 1, got: " + userBody);
    }

    @Test
    void updatesExistingUserViaPut() {
        OnvifProvisioning.Result result = connection().provisionOnvif("existing", "NewPass99");

        assertEquals("ONVIF user 'existing' already existed — password updated", result.userStatus());
        String userBody = capturedBodies.get("PUT /ISAPI/Security/ONVIF/users/1");
        assertNotNull(userBody, "existing user must be updated via PUT on its id");
        assertTrue(userBody.contains("<password>NewPass99</password>"));
    }

    private static String body(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String xml)
            throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/xml");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
