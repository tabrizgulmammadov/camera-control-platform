package com.cameracontrolplatform.driver.hikvision;

import java.util.ArrayList;
import java.util.List;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.cameracontrolplatform.domain.AudioEncoderConfig;
import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.DeviceInformation;
import com.cameracontrolplatform.domain.ErrorCode;
import com.cameracontrolplatform.domain.MediaProfile;
import com.cameracontrolplatform.domain.StreamType;
import com.cameracontrolplatform.domain.VideoEncoderConfig;

/**
 * DOM extraction of Hikvision ISAPI XML payloads. ISAPI documents use a
 * default namespace ({@code http://www.hikvision.com/ver20/XMLSchema}, or
 * ver10 on old firmware), so — like OnvifParsers — everything matches by
 * local name only.
 */
final class IsapiParsers {

    private IsapiParsers() {
    }

    /** {@code GET /ISAPI/System/deviceInfo} → DeviceInformation. */
    static DeviceInformation deviceInformation(Document doc) {
        Element info = firstElement(doc, "DeviceInfo");
        if (info == null) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "Unexpected reply to /ISAPI/System/deviceInfo — this does not look like a Hikvision ISAPI device.");
        }
        return new DeviceInformation(
                "Hikvision",
                childText(info, "model"),
                childText(info, "firmwareVersion"),
                childText(info, "serialNumber"));
    }

    /**
     * {@code GET /ISAPI/Streaming/channels} → MediaProfiles. Channel ids are
     * {@code <camera><stream>}: X01 → MAIN, X02 → SUB. Hikvision reports
     * {@code maxFrameRate} multiplied by 100 (2500 = 25 fps) — normalized here.
     * The RTSP URI is the well-known {@code /Streaming/Channels/{id}} path.
     */
    static List<MediaProfile> channels(Document doc, String host) {
        List<MediaProfile> result = new ArrayList<>();
        int index = 0;
        for (Element channel : elements(doc, "StreamingChannel")) {
            String id = childText(channel, "id");
            String name = childText(channel, "channelName");
            if (name == null) {
                name = "Channel " + id;
            }

            VideoEncoderConfig video = null;
            Element videoEl = childElement(channel, "Video");
            if (videoEl != null) {
                VideoEncoderConfig.Resolution res = null;
                Integer width = parseIntOrNull(childText(videoEl, "videoResolutionWidth"));
                Integer height = parseIntOrNull(childText(videoEl, "videoResolutionHeight"));
                if (width != null && height != null) {
                    res = new VideoEncoderConfig.Resolution(width, height);
                }
                video = new VideoEncoderConfig(
                        normaliseVideoEncoding(childText(videoEl, "videoCodecType")),
                        res,
                        normaliseFrameRate(parseIntOrNull(childText(videoEl, "maxFrameRate"))),
                        bitrateKbps(videoEl),
                        parseDoubleOrNull(childText(videoEl, "fixedQuality")),
                        parseIntOrNull(childText(videoEl, "GovLength")),
                        firstNonNull(childText(videoEl, "H264Profile"), childText(videoEl, "H265Profile")));
            }

            AudioEncoderConfig audio = null;
            Element audioEl = childElement(channel, "Audio");
            if (audioEl != null && "true".equalsIgnoreCase(childText(audioEl, "enabled"))) {
                audio = new AudioEncoderConfig(
                        childText(audioEl, "audioCompressionType"),
                        parseIntOrNull(childText(audioEl, "audioBitRate")),
                        parseIntOrNull(childText(audioEl, "audioSamplingRate")));
            }

            result.add(new MediaProfile(
                    id, name, streamType(id, name, index), video, audio, rtspUri(host, id)));
            index++;
        }
        return result;
    }

    static String rtspUri(String host, String channelId) {
        return "rtsp://" + host + ":554/Streaming/Channels/" + channelId;
    }

    /** Hikvision channel id convention: {camera}{01|02|..} — X01 main, X02 sub. */
    private static StreamType streamType(String id, String name, int index) {
        Integer channelId = parseIntOrNull(id);
        if (channelId != null && channelId >= 100) {
            int stream = channelId % 100;
            if (stream == 1) {
                return StreamType.MAIN;
            }
            if (stream == 2) {
                return StreamType.SUB;
            }
            return StreamType.OTHER;
        }
        return StreamType.classify(name, index);
    }

    /** Hikvision reports fps ×100 (e.g. 2500 = 25 fps, 1250 = 12.5 fps). */
    private static Integer normaliseFrameRate(Integer maxFrameRate) {
        if (maxFrameRate == null) {
            return null;
        }
        return maxFrameRate >= 100 ? Math.round(maxFrameRate / 100f) : maxFrameRate;
    }

    /** CBR channels carry constantBitRate; VBR ones cap at vbrUpperCap. */
    private static Integer bitrateKbps(Element videoEl) {
        String control = childText(videoEl, "videoQualityControlType");
        Integer cbr = parseIntOrNull(childText(videoEl, "constantBitRate"));
        Integer vbr = parseIntOrNull(childText(videoEl, "vbrUpperCap"));
        if ("VBR".equalsIgnoreCase(control)) {
            return vbr != null ? vbr : cbr;
        }
        return cbr != null ? cbr : vbr;
    }

    private static String normaliseVideoEncoding(String encoding) {
        if (encoding == null) {
            return null;
        }
        String e = encoding.trim().toUpperCase();
        return switch (e) {
            case "H264", "H.264" -> "H264";
            case "H265", "H.265", "HEVC" -> "H265";
            case "JPEG", "MJPEG" -> "JPEG";
            default -> e;
        };
    }

    /**
     * ISAPI write replies: {@code <ResponseStatus><statusCode>1</statusCode>
     * <statusString>OK</statusString></ResponseStatus>}. Code 1 = OK,
     * 7 = OK but reboot required. Returns null when the reply carries no
     * ResponseStatus (some firmware answers writes with the resource itself).
     */
    static ResponseStatus responseStatus(Document doc) {
        Element status = firstElement(doc, "ResponseStatus");
        if (status == null) {
            return null;
        }
        return new ResponseStatus(
                parseIntOrNull(childText(status, "statusCode")),
                childText(status, "statusString"));
    }

    record ResponseStatus(Integer code, String message) {
        boolean ok() {
            return code != null && (code == 1 || code == 7);
        }

        boolean rebootRequired() {
            return code != null && code == 7;
        }
    }

    /** {@code GET /ISAPI/Security/ONVIF/users} → id/userName pairs. */
    static List<OnvifUser> onvifUsers(Document doc) {
        List<OnvifUser> users = new ArrayList<>();
        for (Element user : elements(doc, "User")) {
            users.add(new OnvifUser(
                    parseIntOrNull(childText(user, "id")),
                    childText(user, "userName")));
        }
        return users;
    }

    record OnvifUser(Integer id, String userName) {
    }

    // ---- small DOM helpers (namespace-tolerant, matching by local name) ----

    private static Element firstElement(Document doc, String localName) {
        List<Element> found = elements(doc, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> elements(Document doc, String localName) {
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(
                    "//*[local-name()='" + localName + "']", doc, XPathConstants.NODESET);
            List<Element> result = new ArrayList<>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element el) {
                    result.add(el);
                }
            }
            return result;
        } catch (XPathExpressionException e) {
            throw new CameraException(ErrorCode.INTERNAL, "XPath evaluation failed", e);
        }
    }

    private static Element childElement(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName) {
        Element child = childElement(parent, localName);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
