package com.cameracontrolplatform.driver.onvif;

/**
 * Realistic sample SOAP responses modeled on real Hikvision / Axis / Bosch
 * devices. Namespace prefixes deliberately vary between fixtures (tds/trt/tt,
 * ns1/aa, default namespace) to prove parsing is namespace-aware and
 * prefix-agnostic.
 */
final class OnvifSoapFixtures {

    private OnvifSoapFixtures() {
    }

    static String envelope(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
                <env:Body>%s</env:Body>
                </env:Envelope>""".formatted(body);
    }

    /** Hikvision-style GetDeviceInformationResponse (tds prefix). */
    static final String DEVICE_INFO_HIKVISION = envelope("""
            <tds:GetDeviceInformationResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
              <tds:Manufacturer>HIKVISION</tds:Manufacturer>
              <tds:Model>DS-2CD2143G2-I</tds:Model>
              <tds:FirmwareVersion>V5.7.1 build 220118</tds:FirmwareVersion>
              <tds:SerialNumber>DS-2CD2143G2-I20220123AAWRD12345678</tds:SerialNumber>
              <tds:HardwareId>88</tds:HardwareId>
            </tds:GetDeviceInformationResponse>""");

    /** Axis-style GetDeviceInformationResponse using an unusual prefix. */
    static final String DEVICE_INFO_AXIS = envelope("""
            <aa:GetDeviceInformationResponse xmlns:aa="http://www.onvif.org/ver10/device/wsdl">
              <aa:Manufacturer>AXIS</aa:Manufacturer>
              <aa:Model>P3245-LVE</aa:Model>
              <aa:FirmwareVersion>10.12.114</aa:FirmwareVersion>
              <aa:SerialNumber>ACCC8EF12345</aa:SerialNumber>
            </aa:GetDeviceInformationResponse>""");

    /** GetCapabilities answer carrying the media XAddr (Hikvision layout). */
    static String capabilities(String mediaXAddr) {
        return envelope("""
                <tds:GetCapabilitiesResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                    xmlns:tt="http://www.onvif.org/ver10/schema">
                  <tds:Capabilities>
                    <tt:Device>
                      <tt:XAddr>%s/onvif/device_service</tt:XAddr>
                    </tt:Device>
                    <tt:Media>
                      <tt:XAddr>%s</tt:XAddr>
                      <tt:StreamingCapabilities>
                        <tt:RTPMulticast>false</tt:RTPMulticast>
                        <tt:RTP_TCP>true</tt:RTP_TCP>
                        <tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP>
                      </tt:StreamingCapabilities>
                    </tt:Media>
                  </tds:Capabilities>
                </tds:GetCapabilitiesResponse>""".formatted(mediaXAddr, mediaXAddr));
    }

    /** Bosch-style GetServices answer (default namespace, no prefixes). */
    static String services(String mediaXAddr) {
        return envelope("""
                <GetServicesResponse xmlns="http://www.onvif.org/ver10/device/wsdl">
                  <Service>
                    <Namespace>http://www.onvif.org/ver10/device/wsdl</Namespace>
                    <XAddr>%s/onvif/device_service</XAddr>
                    <Version><Major>21</Major><Minor>6</Minor></Version>
                  </Service>
                  <Service>
                    <Namespace>http://www.onvif.org/ver10/media/wsdl</Namespace>
                    <XAddr>%s</XAddr>
                    <Version><Major>21</Major><Minor>6</Minor></Version>
                  </Service>
                </GetServicesResponse>""".formatted(mediaXAddr, mediaXAddr));
    }

    /**
     * Hikvision-style GetProfilesResponse: mainStream H264 1080p with G711
     * audio, subStream H265 (Media2-ish Profile child), plus a JPEG snapshot
     * profile with no audio and no rate control.
     */
    static final String PROFILES_HIKVISION = envelope("""
            <trt:GetProfilesResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
              <trt:Profiles token="Profile_1" fixed="true">
                <tt:Name>mainStream</tt:Name>
                <tt:VideoSourceConfiguration token="VideoSourceToken">
                  <tt:Name>VideoSourceConfig</tt:Name>
                  <tt:SourceToken>VideoSource_1</tt:SourceToken>
                  <tt:Bounds height="2160" width="3840" y="0" x="0"/>
                </tt:VideoSourceConfiguration>
                <tt:VideoEncoderConfiguration token="VideoEncoderToken_1">
                  <tt:Name>VideoEncoder_1</tt:Name>
                  <tt:UseCount>1</tt:UseCount>
                  <tt:Encoding>H264</tt:Encoding>
                  <tt:Resolution>
                    <tt:Width>1920</tt:Width>
                    <tt:Height>1080</tt:Height>
                  </tt:Resolution>
                  <tt:Quality>4.0</tt:Quality>
                  <tt:RateControl>
                    <tt:FrameRateLimit>25</tt:FrameRateLimit>
                    <tt:EncodingInterval>1</tt:EncodingInterval>
                    <tt:BitrateLimit>4096</tt:BitrateLimit>
                  </tt:RateControl>
                  <tt:H264>
                    <tt:GovLength>50</tt:GovLength>
                    <tt:H264Profile>Main</tt:H264Profile>
                  </tt:H264>
                </tt:VideoEncoderConfiguration>
                <tt:AudioEncoderConfiguration token="AudioEncoderToken_1">
                  <tt:Name>AudioEncoder_1</tt:Name>
                  <tt:Encoding>G711</tt:Encoding>
                  <tt:Bitrate>64</tt:Bitrate>
                  <tt:SampleRate>8</tt:SampleRate>
                </tt:AudioEncoderConfiguration>
              </trt:Profiles>
              <trt:Profiles token="Profile_2" fixed="true">
                <tt:Name>subStream</tt:Name>
                <tt:VideoEncoderConfiguration token="VideoEncoderToken_2">
                  <tt:Name>VideoEncoder_2</tt:Name>
                  <tt:Encoding>H265</tt:Encoding>
                  <tt:Resolution>
                    <tt:Width>640</tt:Width>
                    <tt:Height>360</tt:Height>
                  </tt:Resolution>
                  <tt:Quality>3.0</tt:Quality>
                  <tt:RateControl>
                    <tt:FrameRateLimit>12</tt:FrameRateLimit>
                    <tt:BitrateLimit>512</tt:BitrateLimit>
                  </tt:RateControl>
                  <tt:GovLength>25</tt:GovLength>
                  <tt:Profile>Main</tt:Profile>
                </tt:VideoEncoderConfiguration>
                <tt:AudioEncoderConfiguration token="AudioEncoderToken_1">
                  <tt:Name>AudioEncoder_1</tt:Name>
                  <tt:Encoding>AAC</tt:Encoding>
                  <tt:Bitrate>64</tt:Bitrate>
                  <tt:SampleRate>16</tt:SampleRate>
                </tt:AudioEncoderConfiguration>
              </trt:Profiles>
              <trt:Profiles token="Profile_3">
                <tt:Name>snapshotProfile</tt:Name>
                <tt:VideoEncoderConfiguration token="VideoEncoderToken_3">
                  <tt:Name>VideoEncoder_3</tt:Name>
                  <tt:Encoding>JPEG</tt:Encoding>
                  <tt:Resolution>
                    <tt:Width>704</tt:Width>
                    <tt:Height>576</tt:Height>
                  </tt:Resolution>
                  <tt:Quality>3.0</tt:Quality>
                </tt:VideoEncoderConfiguration>
              </trt:Profiles>
            </trt:GetProfilesResponse>""");

    /** Axis-style profiles: different prefixes, names without main/sub — index heuristic. */
    static final String PROFILES_AXIS = envelope("""
            <ns1:GetProfilesResponse xmlns:ns1="http://www.onvif.org/ver10/media/wsdl"
                xmlns:ns2="http://www.onvif.org/ver10/schema">
              <ns1:Profiles token="profile_1_h264" fixed="true">
                <ns2:Name>profile_1 h264</ns2:Name>
                <ns2:VideoEncoderConfiguration token="default_1_h264">
                  <ns2:Name>Video encoder 1</ns2:Name>
                  <ns2:Encoding>H.264</ns2:Encoding>
                  <ns2:Resolution>
                    <ns2:Width>1280</ns2:Width>
                    <ns2:Height>720</ns2:Height>
                  </ns2:Resolution>
                  <ns2:Quality>70</ns2:Quality>
                  <ns2:RateControl>
                    <ns2:FrameRateLimit>30</ns2:FrameRateLimit>
                    <ns2:BitrateLimit>2147483</ns2:BitrateLimit>
                  </ns2:RateControl>
                  <ns2:H264>
                    <ns2:GovLength>32</ns2:GovLength>
                    <ns2:H264Profile>High</ns2:H264Profile>
                  </ns2:H264>
                </ns2:VideoEncoderConfiguration>
              </ns1:Profiles>
              <ns1:Profiles token="profile_2_jpeg">
                <ns2:Name>profile_2 jpeg</ns2:Name>
                <ns2:VideoEncoderConfiguration token="default_2_jpeg">
                  <ns2:Name>Video encoder 2</ns2:Name>
                  <ns2:Encoding>MJPEG</ns2:Encoding>
                  <ns2:Resolution>
                    <ns2:Width>640</ns2:Width>
                    <ns2:Height>480</ns2:Height>
                  </ns2:Resolution>
                  <ns2:Quality>60</ns2:Quality>
                </ns2:VideoEncoderConfiguration>
              </ns1:Profiles>
            </ns1:GetProfilesResponse>""");

    static String streamUri(String uri) {
        return envelope("""
                <trt:GetStreamUriResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                    xmlns:tt="http://www.onvif.org/ver10/schema">
                  <trt:MediaUri>
                    <tt:Uri>%s</tt:Uri>
                    <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                    <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
                    <tt:Timeout>PT60S</tt:Timeout>
                  </trt:MediaUri>
                </trt:GetStreamUriResponse>""".formatted(uri));
    }

    /** SOAP 1.2 fault a camera returns for bad WSSE credentials. */
    static final String FAULT_NOT_AUTHORIZED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"
                xmlns:ter="http://www.onvif.org/ver10/error">
              <env:Body>
                <env:Fault>
                  <env:Code>
                    <env:Value>env:Sender</env:Value>
                    <env:Subcode><env:Value>ter:NotAuthorized</env:Value></env:Subcode>
                  </env:Code>
                  <env:Reason><env:Text xml:lang="en">The action requested requires authorization and the sender is not authorized</env:Text></env:Reason>
                </env:Fault>
              </env:Body>
            </env:Envelope>""";
}
