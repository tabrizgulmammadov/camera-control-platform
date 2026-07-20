package com.cameracheck.driver.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.cameracheck.domain.CameraException;
import com.cameracheck.domain.DeviceInformation;
import com.cameracheck.domain.ErrorCode;
import com.cameracheck.domain.MediaProfile;
import com.cameracheck.domain.StreamType;

class OnvifParsersTest {

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("fixture did not parse", e);
        }
    }

    @Test
    void deviceInformationFromHikvisionStyleReply() {
        DeviceInformation info = OnvifParsers.deviceInformation(parse(OnvifSoapFixtures.DEVICE_INFO_HIKVISION));
        assertEquals("HIKVISION", info.manufacturer());
        assertEquals("DS-2CD2143G2-I", info.model());
        assertEquals("V5.7.1 build 220118", info.firmwareVersion());
        assertEquals("DS-2CD2143G2-I20220123AAWRD12345678", info.serialNumber());
    }

    @Test
    void deviceInformationIsPrefixAgnostic() {
        DeviceInformation info = OnvifParsers.deviceInformation(parse(OnvifSoapFixtures.DEVICE_INFO_AXIS));
        assertEquals("AXIS", info.manufacturer());
        assertEquals("P3245-LVE", info.model());
    }

    @Test
    void deviceInformationRejectsNonOnvifReply() {
        CameraException e = assertThrows(CameraException.class,
                () -> OnvifParsers.deviceInformation(parse("<html><body>login</body></html>")));
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    @Test
    void mediaXAddrFromGetCapabilities() {
        String xaddr = OnvifParsers.mediaXAddr(
                parse(OnvifSoapFixtures.capabilities("http://192.168.1.64:80/onvif/Media")));
        assertEquals("http://192.168.1.64:80/onvif/Media", xaddr);
    }

    @Test
    void mediaXAddrFromGetServicesDefaultNamespace() {
        String xaddr = OnvifParsers.mediaXAddr(
                parse(OnvifSoapFixtures.services("http://10.0.0.5:8000/onvif/media_service")));
        assertEquals("http://10.0.0.5:8000/onvif/media_service", xaddr);
    }

    @Test
    void mediaXAddrNullWhenAbsent() {
        assertNull(OnvifParsers.mediaXAddr(parse(OnvifSoapFixtures.DEVICE_INFO_HIKVISION)));
    }

    @Test
    void hikvisionProfilesFullyParsed() {
        List<MediaProfile> profiles = OnvifParsers.profiles(parse(OnvifSoapFixtures.PROFILES_HIKVISION));
        assertEquals(3, profiles.size());

        MediaProfile main = profiles.get(0);
        assertEquals("Profile_1", main.token());
        assertEquals("mainStream", main.name());
        assertEquals(StreamType.MAIN, main.streamType());
        assertEquals("H264", main.videoEncoder().encoding());
        assertEquals(1920, main.videoEncoder().resolution().width());
        assertEquals(1080, main.videoEncoder().resolution().height());
        assertEquals(25, main.videoEncoder().frameRate());
        assertEquals(4096, main.videoEncoder().bitrateKbps());
        assertEquals(4.0, main.videoEncoder().quality());
        assertEquals(50, main.videoEncoder().govLength());
        assertEquals("Main", main.videoEncoder().profile());
        assertEquals("G711", main.audioEncoder().encoding());
        assertEquals(64, main.audioEncoder().bitrateKbps());
        assertEquals(8, main.audioEncoder().sampleRateKhz());

        MediaProfile sub = profiles.get(1);
        assertEquals("Profile_2", sub.token());
        assertEquals(StreamType.SUB, sub.streamType());
        assertEquals("H265", sub.videoEncoder().encoding());
        assertEquals(640, sub.videoEncoder().resolution().width());
        // H265/Media2 style: GovLength and Profile directly under the encoder config
        assertEquals(25, sub.videoEncoder().govLength());
        assertEquals("Main", sub.videoEncoder().profile());
        assertEquals("AAC", sub.audioEncoder().encoding());

        MediaProfile snapshot = profiles.get(2);
        assertEquals(StreamType.OTHER, snapshot.streamType());
        assertEquals("JPEG", snapshot.videoEncoder().encoding());
        assertNull(snapshot.videoEncoder().frameRate());
        assertNull(snapshot.audioEncoder());
    }

    @Test
    void axisProfilesNormalisedEncodingsAndIndexHeuristic() {
        List<MediaProfile> profiles = OnvifParsers.profiles(parse(OnvifSoapFixtures.PROFILES_AXIS));
        assertEquals(2, profiles.size());

        // Names carry no main/sub — heuristic falls back to profile order.
        assertEquals(StreamType.MAIN, profiles.get(0).streamType());
        assertEquals(StreamType.SUB, profiles.get(1).streamType());

        // "H.264" and "MJPEG" get normalised to contract values.
        assertEquals("H264", profiles.get(0).videoEncoder().encoding());
        assertEquals("High", profiles.get(0).videoEncoder().profile());
        assertEquals(32, profiles.get(0).videoEncoder().govLength());
        assertEquals("JPEG", profiles.get(1).videoEncoder().encoding());
    }

    @Test
    void streamTypeHeuristicByName() {
        assertEquals(StreamType.MAIN, StreamType.classify("MainStream", 3));
        assertEquals(StreamType.SUB, StreamType.classify("SubStream", 0));
        assertEquals(StreamType.MAIN, StreamType.classify(null, 0));
        assertEquals(StreamType.SUB, StreamType.classify("whatever", 1));
        assertEquals(StreamType.OTHER, StreamType.classify("third", 2));
    }

    @Test
    void streamUriExtracted() {
        String uri = OnvifParsers.streamUri(parse(OnvifSoapFixtures.streamUri(
                "rtsp://192.168.1.64:554/Streaming/Channels/101?transportmode=unicast")));
        assertEquals("rtsp://192.168.1.64:554/Streaming/Channels/101?transportmode=unicast", uri);
    }

    @Test
    void streamUriNullOnUnexpectedReply() {
        assertNull(OnvifParsers.streamUri(parse(OnvifSoapFixtures.DEVICE_INFO_HIKVISION)));
    }
}
