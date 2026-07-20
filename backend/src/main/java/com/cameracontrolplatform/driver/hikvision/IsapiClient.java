package com.cameracontrolplatform.driver.hikvision;

import java.io.IOException;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.ErrorCode;

/**
 * Minimal Hikvision ISAPI client: GET of XML resources over the JDK
 * HttpClient with HTTP Digest authentication (challenge → compute → retry;
 * Basic fallback when the camera only offers Basic). Error mapping follows
 * the contract: refused/404/non-XML → ONVIF_NOT_ENABLED ("the selected
 * protocol is not answering"), timeout/no-route → DEVICE_UNREACHABLE,
 * 401 after the authenticated retry → ONVIF_AUTH_FAILED.
 */
final class IsapiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** GETs the given ISAPI URL and returns the parsed XML document. */
    Document get(String url, String username, String password) {
        return exchange("GET", url, null, username, password);
    }

    /** PUTs the given XML body to the ISAPI URL and returns the parsed reply. */
    Document put(String url, String xmlBody, String username, String password) {
        return exchange("PUT", url, xmlBody, username, password);
    }

    /** POSTs the given XML body to the ISAPI URL and returns the parsed reply. */
    Document post(String url, String xmlBody, String username, String password) {
        return exchange("POST", url, xmlBody, username, password);
    }

    private Document exchange(String method, String url, String xmlBody,
            String username, String password) {
        HttpResponse<String> response = send(method, url, xmlBody, null);

        if (response.statusCode() == 401) {
            String authorization = answerChallenge(response, method, url, username, password);
            response = send(method, url, xmlBody, authorization);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new CameraException(ErrorCode.ONVIF_AUTH_FAILED,
                        "The device rejected the ISAPI credentials (HTTP " + response.statusCode() + ").");
            }
        } else if (response.statusCode() == 403) {
            throw new CameraException(ErrorCode.ONVIF_AUTH_FAILED,
                    "The device rejected the ISAPI request (HTTP 403).");
        }

        if (response.statusCode() == 404) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "No ISAPI service at " + url + " (HTTP 404) — this does not look like a Hikvision ISAPI device.");
        }
        if (response.statusCode() >= 400) {
            throw new CameraException(ErrorCode.INTERNAL,
                    "ISAPI call failed with HTTP " + response.statusCode());
        }

        String body = response.body();
        if (body == null || !body.trim().startsWith("<") || body.contains("<html")) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "The device answered but not with ISAPI XML — the selected protocol is not answering on this port.");
        }
        return parse(body);
    }

    /** Builds the Authorization header for the 401 challenge (Digest preferred, Basic fallback). */
    private String answerChallenge(HttpResponse<String> response, String method, String url,
            String username, String password) {
        if (username == null || username.isBlank()) {
            throw new CameraException(ErrorCode.ONVIF_AUTH_FAILED,
                    "The device requires authentication — please provide credentials.");
        }
        List<String> challenges = response.headers().allValues("WWW-Authenticate");
        String digest = challenges.stream()
                .filter(c -> c.regionMatches(true, 0, "Digest", 0, 6))
                .findFirst().orElse(null);
        if (digest != null) {
            byte[] cnonceBytes = new byte[8];
            RANDOM.nextBytes(cnonceBytes);
            String cnonce = HexFormat.of().formatHex(cnonceBytes);
            return HttpDigestAuth.authorization(
                    digest, method, pathAndQuery(url), username, password, 1, cnonce);
        }
        boolean basicOffered = challenges.stream()
                .anyMatch(c -> c.regionMatches(true, 0, "Basic", 0, 5));
        if (basicOffered) {
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
            return "Basic " + token;
        }
        throw new CameraException(ErrorCode.ONVIF_AUTH_FAILED,
                "The device requested an unsupported authentication scheme: "
                        + String.join(", ", challenges));
    }

    private static String pathAndQuery(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private HttpResponse<String> send(String method, String url, String body, String authorization) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/xml, text/xml, */*")
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (body != null) {
                builder.header("Content-Type", "application/xml; charset=utf-8");
            }
        } catch (IllegalArgumentException e) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "Invalid ISAPI URL: " + url, e);
        }
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new CameraException(ErrorCode.DEVICE_UNREACHABLE,
                    "Could not reach the device at " + url + " (connect timed out).", e);
        } catch (ConnectException e) {
            // Host is up but nothing listens on this port — protocol not answering.
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "Could not connect to the ISAPI service at " + url
                            + " — ISAPI may be disabled on the device, or the port is wrong.", e);
        } catch (HttpTimeoutException e) {
            throw new CameraException(ErrorCode.DEVICE_UNREACHABLE,
                    "The device did not answer the ISAPI request in time.", e);
        } catch (IOException e) {
            if (e instanceof UnknownHostException || e.getCause() instanceof UnknownHostException) {
                throw new CameraException(ErrorCode.DEVICE_UNREACHABLE, "Unknown host: " + url, e);
            }
            if (e instanceof NoRouteToHostException || e.getCause() instanceof NoRouteToHostException) {
                throw new CameraException(ErrorCode.DEVICE_UNREACHABLE,
                        "No route to the device at " + url + ".", e);
            }
            String hint = e.getMessage() != null && e.getMessage().contains("header parser received no bytes")
                    ? " The port accepted the connection but did not speak HTTP — check that you entered the"
                            + " device's HTTP port (usually 80), not the RTSP port (554)."
                    : " The selected protocol may not be enabled.";
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "I/O error talking to the ISAPI service (" + e.getMessage() + ") —" + hint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CameraException(ErrorCode.INTERNAL, "Interrupted while calling the device", e);
        }
    }

    private Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "The device reply could not be parsed as ISAPI XML — the selected protocol is not answering.", e);
        }
    }
}
