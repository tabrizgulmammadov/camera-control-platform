package com.cameracontrolplatform.driver.hikvision;

import java.io.StringWriter;
import java.util.List;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.cameracontrolplatform.domain.CameraConnection;
import com.cameracontrolplatform.domain.CameraEndpoint;
import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.DeviceInformation;
import com.cameracontrolplatform.domain.ErrorCode;
import com.cameracontrolplatform.domain.MediaProfile;
import com.cameracontrolplatform.domain.OnvifProvisioning;
import com.cameracontrolplatform.domain.PtzControl;

/**
 * Hikvision ISAPI session: plain REST/XML against
 * {@code http://{host}:{port}/ISAPI/...} with digest auth handled by
 * {@link IsapiClient}. Also implements ONVIF provisioning: enabling the
 * "Open Network Video Interface" integration protocol and managing ONVIF
 * users via {@code /ISAPI/Security/ONVIF/users}. PTZ continuous move goes
 * through {@code /ISAPI/PTZCtrl/channels/{channel}/continuous}.
 */
final class HikvisionConnection implements CameraConnection, OnvifProvisioning, PtzControl {

    private static final String ISAPI_NS = "http://www.hikvision.com/ver20/XMLSchema";

    private final IsapiClient client;
    private final CameraEndpoint endpoint;
    private final String baseUrl;

    HikvisionConnection(IsapiClient client, CameraEndpoint endpoint) {
        this.client = client;
        this.endpoint = endpoint;
        this.baseUrl = "http://" + endpoint.host() + ":" + endpoint.port();
    }

    @Override
    public DeviceInformation getDeviceInformation() {
        return IsapiParsers.deviceInformation(
                client.get(baseUrl + "/ISAPI/System/deviceInfo",
                        endpoint.username(), endpoint.password()));
    }

    @Override
    public List<MediaProfile> getProfiles() {
        return IsapiParsers.channels(
                client.get(baseUrl + "/ISAPI/Streaming/channels",
                        endpoint.username(), endpoint.password()),
                endpoint.host());
    }

    // ---- PTZ ----

    @Override
    public void continuousMove(int channel, int pan, int tilt) {
        int p = PtzControl.clampSpeed(pan);
        int t = PtzControl.clampSpeed(tilt);
        String url = baseUrl + "/ISAPI/PTZCtrl/channels/" + channel + "/continuous";
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<PTZData xmlns=\"" + ISAPI_NS + "\">"
                + "<pan>" + p + "</pan>"
                + "<tilt>" + t + "</tilt>"
                + "</PTZData>";
        IsapiParsers.ResponseStatus status = IsapiParsers.responseStatus(
                client.put(url, body, endpoint.username(), endpoint.password()));
        if (status != null && !status.ok()) {
            // e.g. a fixed (non-PTZ) camera rejecting the command
            throw new CameraException(ErrorCode.INTERNAL,
                    "The camera rejected the PTZ command: "
                            + (status.message() != null ? status.message() : "statusCode " + status.code()));
        }
    }

    // ---- ONVIF provisioning ----

    @Override
    public boolean isOnvifEnabled() {
        Document integrate = client.get(baseUrl + "/ISAPI/System/Network/Integrate",
                endpoint.username(), endpoint.password());
        Element enable = findOnvifEnable(integrate);
        return enable != null && "true".equalsIgnoreCase(enable.getTextContent().trim());
    }

    @Override
    public Result provisionOnvif(String onvifUsername, String onvifPassword) {
        String integrationStatus = enableIntegrationProtocol();
        String userStatus = upsertOnvifUser(onvifUsername, onvifPassword);
        return new Result(integrationStatus, userStatus,
                "If ONVIF still does not answer, reboot the camera — some firmware applies the "
                        + "integration protocol only after restart.");
    }

    /** GET the Integrate config, flip ONVIF/enable to true, PUT it back. */
    private String enableIntegrationProtocol() {
        String url = baseUrl + "/ISAPI/System/Network/Integrate";
        Document integrate;
        try {
            integrate = client.get(url, endpoint.username(), endpoint.password());
        } catch (CameraException e) {
            if (e.code() == ErrorCode.ONVIF_NOT_ENABLED) {
                throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                        "This firmware does not expose the Integration Protocol via ISAPI ("
                                + e.getMessage() + ") — enable ONVIF in the camera web UI instead.", e);
            }
            throw e;
        }

        Element enable = findOnvifEnable(integrate);
        if (enable == null) {
            throw new CameraException(ErrorCode.INTERNAL,
                    "Unexpected /ISAPI/System/Network/Integrate reply — no ONVIF/enable element found.");
        }
        boolean wasEnabled = "true".equalsIgnoreCase(enable.getTextContent().trim());
        enable.setTextContent("true");

        IsapiParsers.ResponseStatus status = IsapiParsers.responseStatus(
                client.put(url, serialize(integrate), endpoint.username(), endpoint.password()));
        if (status != null && !status.ok()) {
            throw new CameraException(ErrorCode.INTERNAL,
                    "The camera rejected enabling the ONVIF integration protocol: "
                            + status.code() + " " + status.message());
        }
        if (wasEnabled) {
            return "ONVIF integration protocol was already enabled";
        }
        return status != null && status.rebootRequired()
                ? "ONVIF integration protocol enabled (camera reports a reboot is required)"
                : "ONVIF integration protocol enabled";
    }

    /** Create the ONVIF user, or update its password when the name already exists. */
    private String upsertOnvifUser(String username, String password) {
        String usersUrl = baseUrl + "/ISAPI/Security/ONVIF/users";
        List<IsapiParsers.OnvifUser> existing;
        try {
            existing = IsapiParsers.onvifUsers(
                    client.get(usersUrl, endpoint.username(), endpoint.password()));
        } catch (CameraException e) {
            // Integration may already be usable; don't fail the whole provisioning
            // when only user management is unavailable on this firmware.
            return "Could not manage ONVIF users via ISAPI (" + e.getMessage()
                    + ") — create the ONVIF user in the camera web UI.";
        }

        Integer existingId = existing.stream()
                .filter(u -> username.equalsIgnoreCase(u.userName()))
                .map(IsapiParsers.OnvifUser::id)
                .findFirst().orElse(null);
        int id = existingId != null ? existingId
                : existing.stream().map(IsapiParsers.OnvifUser::id)
                        .filter(java.util.Objects::nonNull)
                        .max(Integer::compare).orElse(0) + 1;

        String userXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<User version=\"2.0\" xmlns=\"" + ISAPI_NS + "\">"
                + "<id>" + id + "</id>"
                + "<userName>" + xmlEscape(username) + "</userName>"
                + "<password>" + xmlEscape(password) + "</password>"
                + "<userType>administrator</userType>"
                + "</User>";

        IsapiParsers.ResponseStatus status = IsapiParsers.responseStatus(existingId != null
                ? client.put(usersUrl + "/" + id, userXml, endpoint.username(), endpoint.password())
                : client.post(usersUrl, userXml, endpoint.username(), endpoint.password()));
        if (status != null && !status.ok()) {
            throw new CameraException(ErrorCode.INTERNAL,
                    "The camera rejected the ONVIF user: " + status.code() + " " + status.message()
                            + " (Hikvision requires ONVIF passwords of 8+ chars with letters and digits).");
        }
        return existingId != null
                ? "ONVIF user '" + username + "' already existed — password updated"
                : "ONVIF user '" + username + "' created";
    }

    private static Element findOnvifEnable(Document integrate) {
        NodeList all = integrate.getElementsByTagNameNS("*", "ONVIF");
        for (int i = 0; i < all.getLength(); i++) {
            for (Node n = all.item(i).getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element el && "enable".equals(el.getLocalName())) {
                    return el;
                }
            }
        }
        return null;
    }

    private static String serialize(Document doc) {
        try {
            StringWriter out = new StringWriter();
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new CameraException(ErrorCode.INTERNAL, "Could not serialize ISAPI XML", e);
        }
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
