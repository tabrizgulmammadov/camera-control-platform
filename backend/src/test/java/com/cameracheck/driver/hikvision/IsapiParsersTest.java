package com.cameracheck.driver.hikvision;

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

/** ISAPI XML extraction against realistic namespaced fixtures. */
class IsapiParsersTest {

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deviceInfoMapsToDeviceInformation() {
        DeviceInformation info = IsapiParsers.deviceInformation(parse(IsapiFixtures.DEVICE_INFO));
        assertEquals("Hikvision", info.manufacturer());
        assertEquals("DS-2CD2143G2-I", info.model());
        assertEquals("V5.7.3", info.firmwareVersion());
        assertEquals("DS-2CD2143G2-I20211203AAWRG12345678", info.serialNumber());
    }

    @Test
    void unexpectedDocumentMapsToNotEnabled() {
        CameraException e = assertThrows(CameraException.class,
                () -> IsapiParsers.deviceInformation(parse("<SomethingElse/>")));
        assertEquals(ErrorCode.ONVIF_NOT_ENABLED, e.code());
    }

    @Test
    void channelsMapToProfilesWithNormalisedFps() {
        List<MediaProfile> profiles =
                IsapiParsers.channels(parse(IsapiFixtures.STREAMING_CHANNELS), "192.168.1.64");
        assertEquals(3, profiles.size());

        MediaProfile main = profiles.get(0);
        assertEquals("101", main.token());
        assertEquals(StreamType.MAIN, main.streamType());
        assertEquals("H264", main.videoEncoder().encoding());
        assertEquals(2688, main.videoEncoder().resolution().width());
        assertEquals(1520, main.videoEncoder().resolution().height());
        assertEquals(25, main.videoEncoder().frameRate(), "2500 must normalize to 25 fps");
        assertEquals(4096, main.videoEncoder().bitrateKbps(), "VBR channel uses vbrUpperCap");
        assertEquals(50, main.videoEncoder().govLength());
        assertEquals("Main", main.videoEncoder().profile());
        assertEquals(60.0, main.videoEncoder().quality());
        assertEquals("G.711ulaw", main.audioEncoder().encoding());
        assertEquals("rtsp://192.168.1.64:554/Streaming/Channels/101", main.rtspUri());

        MediaProfile sub = profiles.get(1);
        assertEquals("102", sub.token());
        assertEquals(StreamType.SUB, sub.streamType());
        assertEquals("H265", sub.videoEncoder().encoding());
        assertEquals(13, sub.videoEncoder().frameRate(), "1250 (12.5 fps) rounds to 13");
        assertEquals(512, sub.videoEncoder().bitrateKbps(), "CBR channel uses constantBitRate");
        assertNull(sub.audioEncoder(), "disabled audio must not be reported");
        assertEquals("rtsp://192.168.1.64:554/Streaming/Channels/102", sub.rtspUri());

        MediaProfile third = profiles.get(2);
        assertEquals("103", third.token());
        assertEquals(StreamType.OTHER, third.streamType());
        assertEquals("JPEG", third.videoEncoder().encoding());
        assertEquals(25, third.videoEncoder().frameRate(), "already-plain fps stays untouched");
        assertEquals(2048, third.videoEncoder().bitrateKbps());
    }

    @Test
    void secondCameraChannelIdsStillClassifyByStreamSuffix() {
        String xml = """
                <StreamingChannelList xmlns="http://www.hikvision.com/ver20/XMLSchema">
                  <StreamingChannel><id>201</id><channelName>Camera 02</channelName></StreamingChannel>
                  <StreamingChannel><id>202</id><channelName>Camera 02</channelName></StreamingChannel>
                </StreamingChannelList>
                """;
        List<MediaProfile> profiles = IsapiParsers.channels(parse(xml), "10.0.0.5");
        assertEquals(StreamType.MAIN, profiles.get(0).streamType(), "X01 -> MAIN");
        assertEquals(StreamType.SUB, profiles.get(1).streamType(), "X02 -> SUB");
        assertEquals("rtsp://10.0.0.5:554/Streaming/Channels/201", profiles.get(0).rtspUri());
    }
}
