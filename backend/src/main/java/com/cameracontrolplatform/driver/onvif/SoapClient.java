package com.cameracontrolplatform.driver.onvif;

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
import java.time.Duration;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.ErrorCode;

/**
 * Minimal SOAP 1.2 over HTTP client built on the JDK HttpClient. Envelopes are
 * hand-crafted strings; responses are parsed with the namespace-aware JDK DOM.
 */
final class SoapClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Sends the SOAP 1.2 body wrapped in an envelope (with optional WSSE
     * header) and returns the parsed response document.
     */
    Document call(String endpointUrl, String username, String password, String bodyXml) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>\
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">\
                <s:Header>%s</s:Header>\
                <s:Body>%s</s:Body>\
                </s:Envelope>"""
                .formatted(WsSecurityHeader.build(username, password), bodyXml);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpointUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/soap+xml; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "Invalid ONVIF endpoint URL: " + endpointUrl, e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            // TCP connect never completed: host down or filtered, not "port open but no ONVIF".
            throw new CameraException(ErrorCode.DEVICE_UNREACHABLE,
                    "Could not reach the device at " + endpointUrl + " (connect timed out).", e);
        } catch (ConnectException e) {
            // Connection refused: host is up but nothing listens on this port.
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "Could not connect to the ONVIF service at " + endpointUrl
                            + " — ONVIF may be disabled on the device, or the port is wrong.", e);
        } catch (HttpTimeoutException e) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "The device did not answer the ONVIF request in time — ONVIF may be disabled.", e);
        } catch (IOException e) {
            if (e.getCause() instanceof UnknownHostException || e instanceof UnknownHostException) {
                throw new CameraException(ErrorCode.DEVICE_UNREACHABLE, "Unknown host: " + endpointUrl, e);
            }
            if (e instanceof NoRouteToHostException || e.getCause() instanceof NoRouteToHostException) {
                throw new CameraException(ErrorCode.DEVICE_UNREACHABLE,
                        "No route to the device at " + endpointUrl + ".", e);
            }
            String hint = e.getMessage() != null && e.getMessage().contains("header parser received no bytes")
                    ? " The port accepted the connection but did not speak HTTP — check that you entered the"
                            + " device's HTTP port (usually 80), not the RTSP port (554)."
                    : " ONVIF may be disabled on the device.";
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "I/O error talking to the ONVIF service (" + e.getMessage() + ") —" + hint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CameraException(ErrorCode.INTERNAL, "Interrupted while calling the device", e);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new CameraException(ErrorCode.ONVIF_AUTH_FAILED,
                    "The device rejected the ONVIF credentials (HTTP " + response.statusCode() + ").");
        }
        if (response.statusCode() == 404) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "No ONVIF service at " + endpointUrl + " (HTTP 404) — ONVIF is likely disabled on the device. "
                            + "On Hikvision cameras enable 'Open Network Video Interface' under "
                            + "Configuration > Network > Integration Protocol (and add an ONVIF user), "
                            + "or use the Hikvision (ISAPI) driver instead.");
        }

        String body = response.body();
        if (body == null || !body.contains("Envelope")) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "The device answered but not with SOAP — ONVIF may be disabled on this port.");
        }

        Document doc = parse(body);
        checkFault(doc, response.statusCode());

        if (response.statusCode() >= 400) {
            throw new CameraException(ErrorCode.INTERNAL,
                    "ONVIF call failed with HTTP " + response.statusCode());
        }
        return doc;
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
                    "The device reply could not be parsed as SOAP — ONVIF may be disabled.", e);
        }
    }

    /** Detects SOAP 1.2 faults and maps NotAuthorized to ONVIF_AUTH_FAILED. */
    private void checkFault(Document doc, int httpStatus) {
        var faults = doc.getElementsByTagNameNS("http://www.w3.org/2003/05/soap-envelope", "Fault");
        if (faults.getLength() == 0) {
            return;
        }
        String faultText = faults.item(0).getTextContent();
        String lowered = faultText == null ? "" : faultText.toLowerCase();
        if (lowered.contains("notauthorized") || lowered.contains("not authorized")
                || lowered.contains("authority failure") || lowered.contains("failedauthentication")
                || httpStatus == 401) {
            throw new CameraException(ErrorCode.ONVIF_AUTH_FAILED,
                    "The device rejected the ONVIF credentials (SOAP fault: NotAuthorized).");
        }
        throw new CameraException(ErrorCode.INTERNAL,
                "The device returned a SOAP fault: " + compact(faultText));
    }

    private static String compact(String s) {
        if (s == null) {
            return "";
        }
        String c = s.replaceAll("\\s+", " ").trim();
        return c.length() > 300 ? c.substring(0, 300) + "..." : c;
    }
}
