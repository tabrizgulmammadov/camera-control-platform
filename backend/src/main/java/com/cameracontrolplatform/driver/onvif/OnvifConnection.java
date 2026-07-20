package com.cameracontrolplatform.driver.onvif;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;

import com.cameracontrolplatform.domain.CameraConnection;
import com.cameracontrolplatform.domain.CameraEndpoint;
import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.DeviceInformation;
import com.cameracontrolplatform.domain.ErrorCode;
import com.cameracontrolplatform.domain.MediaProfile;
import com.cameracontrolplatform.domain.PtzControl;

/**
 * ONVIF session: talks SOAP 1.2 to the device service at
 * http://{host}:{port}/onvif/device_service, discovers the media service XAddr
 * via GetCapabilities (falling back to GetServices), then queries profiles and
 * per-profile RTSP stream URIs. PTZ continuous move goes through the PTZ
 * service XAddr (discovered the same way) using the media profile token of the
 * requested channel.
 */
final class OnvifConnection implements CameraConnection, PtzControl {

    private static final String DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl";
    private static final String MEDIA_NS = "http://www.onvif.org/ver10/media/wsdl";
    private static final String PTZ_NS = "http://www.onvif.org/ver20/ptz/wsdl";
    private static final String SCHEMA_NS = "http://www.onvif.org/ver10/schema";

    private final SoapClient soap;
    private final CameraEndpoint endpoint;
    private final String deviceServiceUrl;
    private String mediaServiceUrl;
    private String ptzServiceUrl;
    private boolean ptzXAddrResolved;

    OnvifConnection(SoapClient soap, CameraEndpoint endpoint) {
        this.soap = soap;
        this.endpoint = endpoint;
        this.deviceServiceUrl = "http://" + endpoint.host() + ":" + endpoint.port() + "/onvif/device_service";
    }

    @Override
    public DeviceInformation getDeviceInformation() {
        Document doc = call(deviceServiceUrl,
                "<tds:GetDeviceInformation xmlns:tds=\"" + DEVICE_NS + "\"/>");
        return OnvifParsers.deviceInformation(doc);
    }

    @Override
    public List<MediaProfile> getProfiles() {
        String mediaUrl = mediaServiceUrl();
        Document doc = call(mediaUrl, "<trt:GetProfiles xmlns:trt=\"" + MEDIA_NS + "\"/>");
        List<MediaProfile> profiles = OnvifParsers.profiles(doc);

        List<MediaProfile> withUris = new ArrayList<>(profiles.size());
        for (MediaProfile profile : profiles) {
            withUris.add(profile.withRtspUri(streamUri(mediaUrl, profile.token())));
        }
        return withUris;
    }

    // ---- PTZ ----

    @Override
    public void continuousMove(int channel, int pan, int tilt) {
        int p = PtzControl.clampSpeed(pan);
        int t = PtzControl.clampSpeed(tilt);
        String ptzUrl = ptzServiceUrl();
        if (ptzUrl == null) {
            throw new CameraException(ErrorCode.BAD_REQUEST,
                    "The device does not advertise an ONVIF PTZ service — it is likely a fixed camera.");
        }
        String token = profileTokenForChannel(channel);
        if (p == 0 && t == 0) {
            call(ptzUrl, """
                    <tptz:Stop xmlns:tptz="%s">\
                    <tptz:ProfileToken>%s</tptz:ProfileToken>\
                    <tptz:PanTilt>true</tptz:PanTilt>\
                    <tptz:Zoom>true</tptz:Zoom>\
                    </tptz:Stop>"""
                    .formatted(PTZ_NS, WsSecurityHeader.xmlEscape(token)));
            return;
        }
        call(ptzUrl, """
                <tptz:ContinuousMove xmlns:tptz="%s" xmlns:tt="%s">\
                <tptz:ProfileToken>%s</tptz:ProfileToken>\
                <tptz:Velocity><tt:PanTilt x="%s" y="%s"/></tptz:Velocity>\
                </tptz:ContinuousMove>"""
                .formatted(PTZ_NS, SCHEMA_NS, WsSecurityHeader.xmlEscape(token),
                        speedToVelocity(p), speedToVelocity(t)));
    }

    /** Contract speed −100…100 → ONVIF normalized velocity −1.0…1.0. */
    private static String speedToVelocity(int speed) {
        return String.format(java.util.Locale.ROOT, "%.2f", speed / 100.0);
    }

    /** 1-based channel → token of the Nth media profile. */
    private String profileTokenForChannel(int channel) {
        Document doc = call(mediaServiceUrl(), "<trt:GetProfiles xmlns:trt=\"" + MEDIA_NS + "\"/>");
        List<MediaProfile> profiles = OnvifParsers.profiles(doc);
        if (profiles.isEmpty()) {
            throw new CameraException(ErrorCode.INTERNAL, "The device reported no media profiles.");
        }
        if (channel < 1 || channel > profiles.size()) {
            throw new CameraException(ErrorCode.BAD_REQUEST,
                    "channel " + channel + " is out of range — the device has "
                            + profiles.size() + " profile(s).");
        }
        return profiles.get(channel - 1).token();
    }

    private synchronized String ptzServiceUrl() {
        if (ptzXAddrResolved) {
            return ptzServiceUrl;
        }
        String xaddr = null;
        try {
            Document caps = call(deviceServiceUrl, """
                    <tds:GetCapabilities xmlns:tds="%s">\
                    <tds:Category>All</tds:Category>\
                    </tds:GetCapabilities>""".formatted(DEVICE_NS));
            xaddr = OnvifParsers.ptzXAddr(caps);
        } catch (CameraException e) {
            if (e.code() == ErrorCode.ONVIF_AUTH_FAILED) {
                throw e;
            }
            // fall through to GetServices
        }
        if (xaddr == null) {
            try {
                Document services = call(deviceServiceUrl, """
                        <tds:GetServices xmlns:tds="%s">\
                        <tds:IncludeCapability>false</tds:IncludeCapability>\
                        </tds:GetServices>""".formatted(DEVICE_NS));
                xaddr = OnvifParsers.ptzXAddr(services);
            } catch (CameraException e) {
                if (e.code() == ErrorCode.ONVIF_AUTH_FAILED) {
                    throw e;
                }
            }
        }
        ptzServiceUrl = xaddr != null ? rewriteHost(xaddr) : null;
        ptzXAddrResolved = true;
        return ptzServiceUrl;
    }

    private String streamUri(String mediaUrl, String profileToken) {
        String body = """
                <trt:GetStreamUri xmlns:trt="%s" xmlns:tt="http://www.onvif.org/ver10/schema">\
                <trt:StreamSetup>\
                <tt:Stream>RTP-Unicast</tt:Stream>\
                <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>\
                </trt:StreamSetup>\
                <trt:ProfileToken>%s</trt:ProfileToken>\
                </trt:GetStreamUri>"""
                .formatted(MEDIA_NS, WsSecurityHeader.xmlEscape(profileToken));
        try {
            return OnvifParsers.streamUri(call(mediaUrl, body));
        } catch (CameraException e) {
            // A profile without a live stream should not fail the whole listing.
            return null;
        }
    }

    private synchronized String mediaServiceUrl() {
        if (mediaServiceUrl != null) {
            return mediaServiceUrl;
        }
        String xaddr = null;
        try {
            Document caps = call(deviceServiceUrl, """
                    <tds:GetCapabilities xmlns:tds="%s">\
                    <tds:Category>All</tds:Category>\
                    </tds:GetCapabilities>""".formatted(DEVICE_NS));
            xaddr = OnvifParsers.mediaXAddr(caps);
        } catch (CameraException e) {
            if (e.code() == com.cameracontrolplatform.domain.ErrorCode.ONVIF_AUTH_FAILED) {
                throw e;
            }
            // fall through to GetServices
        }
        if (xaddr == null) {
            try {
                Document services = call(deviceServiceUrl, """
                        <tds:GetServices xmlns:tds="%s">\
                        <tds:IncludeCapability>false</tds:IncludeCapability>\
                        </tds:GetServices>""".formatted(DEVICE_NS));
                xaddr = OnvifParsers.mediaXAddr(services);
            } catch (CameraException e) {
                if (e.code() == com.cameracontrolplatform.domain.ErrorCode.ONVIF_AUTH_FAILED) {
                    throw e;
                }
            }
        }
        mediaServiceUrl = xaddr != null ? rewriteHost(xaddr) : deviceServiceUrl;
        return mediaServiceUrl;
    }

    /**
     * Cameras behind NAT often advertise an XAddr with an internal IP; keep the
     * path but pin host/port to what the user gave us.
     */
    private String rewriteHost(String xaddr) {
        try {
            java.net.URI uri = java.net.URI.create(xaddr);
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank()
                    ? "/onvif/media_service" : uri.getRawPath();
            return "http://" + endpoint.host() + ":" + endpoint.port() + path;
        } catch (IllegalArgumentException e) {
            return xaddr;
        }
    }

    private Document call(String url, String body) {
        return soap.call(url, endpoint.username(), endpoint.password(), body);
    }
}
