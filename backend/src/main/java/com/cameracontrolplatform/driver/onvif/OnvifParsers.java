package com.cameracontrolplatform.driver.onvif;

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
 * DOM/XPath extraction of ONVIF response payloads. XPath uses local-name()
 * matching so it is robust to whatever prefixes the camera chooses, while the
 * documents themselves are parsed namespace-aware.
 */
final class OnvifParsers {

    private OnvifParsers() {
    }

    static DeviceInformation deviceInformation(Document doc) {
        Element resp = firstElement(doc, "GetDeviceInformationResponse");
        if (resp == null) {
            throw new CameraException(ErrorCode.ONVIF_NOT_ENABLED,
                    "Unexpected reply to GetDeviceInformation — ONVIF may be disabled.");
        }
        return new DeviceInformation(
                childText(resp, "Manufacturer"),
                childText(resp, "Model"),
                childText(resp, "FirmwareVersion"),
                childText(resp, "SerialNumber"));
    }

    /** XAddr of the media service, from GetCapabilities or GetServices replies. */
    static String mediaXAddr(Document doc) {
        // GetCapabilities: .../Capabilities/Media/XAddr
        Element media = firstElement(doc, "Media");
        if (media != null) {
            String xaddr = childText(media, "XAddr");
            if (xaddr != null && !xaddr.isBlank()) {
                return xaddr.trim();
            }
        }
        // GetServices: Service entries with Namespace + XAddr
        for (Element service : elements(doc, "Service")) {
            String ns = childText(service, "Namespace");
            if (ns != null && ns.contains("/media/")) {
                String xaddr = childText(service, "XAddr");
                if (xaddr != null && !xaddr.isBlank()) {
                    return xaddr.trim();
                }
            }
        }
        return null;
    }

    /** XAddr of the PTZ service, from GetCapabilities or GetServices replies. */
    static String ptzXAddr(Document doc) {
        // GetCapabilities: .../Capabilities/PTZ/XAddr
        Element ptz = firstElement(doc, "PTZ");
        if (ptz != null) {
            String xaddr = childText(ptz, "XAddr");
            if (xaddr != null && !xaddr.isBlank()) {
                return xaddr.trim();
            }
        }
        // GetServices: Service entries with Namespace + XAddr
        for (Element service : elements(doc, "Service")) {
            String ns = childText(service, "Namespace");
            if (ns != null && ns.contains("/ptz/")) {
                String xaddr = childText(service, "XAddr");
                if (xaddr != null && !xaddr.isBlank()) {
                    return xaddr.trim();
                }
            }
        }
        return null;
    }

    /** Profiles with embedded video/audio encoder configurations. */
    static List<MediaProfile> profiles(Document doc) {
        List<MediaProfile> result = new ArrayList<>();
        List<Element> profileElements = elements(doc, "Profiles");
        int index = 0;
        for (Element profile : profileElements) {
            String token = profile.getAttribute("token");
            String name = childText(profile, "Name");

            VideoEncoderConfig video = null;
            Element vec = childElement(profile, "VideoEncoderConfiguration");
            if (vec != null) {
                Element resolution = childElement(vec, "Resolution");
                VideoEncoderConfig.Resolution res = null;
                if (resolution != null) {
                    res = new VideoEncoderConfig.Resolution(
                            parseInt(childText(resolution, "Width"), 0),
                            parseInt(childText(resolution, "Height"), 0));
                }
                Element rateControl = childElement(vec, "RateControl");
                Integer frameRate = null;
                Integer bitrate = null;
                if (rateControl != null) {
                    frameRate = parseIntOrNull(childText(rateControl, "FrameRateLimit"));
                    bitrate = parseIntOrNull(childText(rateControl, "BitrateLimit"));
                }
                String encoding = normaliseVideoEncoding(childText(vec, "Encoding"));
                Integer govLength = null;
                String encoderProfile = null;
                Element h264 = childElement(vec, "H264");
                Element mpeg4 = childElement(vec, "MPEG4");
                if (h264 != null) {
                    govLength = parseIntOrNull(childText(h264, "GovLength"));
                    encoderProfile = childText(h264, "H264Profile");
                } else if (mpeg4 != null) {
                    govLength = parseIntOrNull(childText(mpeg4, "GovLength"));
                    encoderProfile = childText(mpeg4, "Mpeg4Profile");
                }
                // Media2-style / H.265 cameras put profile in a "Profile" child
                if (encoderProfile == null) {
                    encoderProfile = childText(vec, "Profile");
                }
                if (govLength == null) {
                    govLength = parseIntOrNull(childText(vec, "GovLength"));
                }
                video = new VideoEncoderConfig(
                        encoding, res, frameRate, bitrate,
                        parseDoubleOrNull(childText(vec, "Quality")),
                        govLength, encoderProfile);
            }

            AudioEncoderConfig audio = null;
            Element aec = childElement(profile, "AudioEncoderConfiguration");
            if (aec != null) {
                audio = new AudioEncoderConfig(
                        childText(aec, "Encoding"),
                        parseIntOrNull(childText(aec, "Bitrate")),
                        parseIntOrNull(childText(aec, "SampleRate")));
            }

            result.add(new MediaProfile(token, name,
                    StreamType.classify(name, index), video, audio, null));
            index++;
        }
        return result;
    }

    static String streamUri(Document doc) {
        Element response = firstElement(doc, "GetStreamUriResponse");
        if (response == null) {
            return null;
        }
        Element mediaUri = childElement(response, "MediaUri");
        Element holder = mediaUri != null ? mediaUri : response;
        String uri = childText(holder, "Uri");
        return uri == null ? null : uri.trim();
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

    // ---- small DOM helpers -------------------------------------------------

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

    /** Direct child element with the given local name (namespace-agnostic). */
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

    private static int parseInt(String s, int fallback) {
        Integer v = parseIntOrNull(s);
        return v == null ? fallback : v;
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
