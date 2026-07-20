package com.cameracontrolplatform.driver.hikvision;

/** Canned Hikvision ISAPI XML replies, as real cameras produce them. */
final class IsapiFixtures {

    private IsapiFixtures() {
    }

    static final String DEVICE_INFO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <DeviceInfo version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
                <deviceName>IPCAMERA</deviceName>
                <deviceID>8c2f4d38-53a4-11ec-8232-988b0a04d61e</deviceID>
                <deviceDescription>IPCamera</deviceDescription>
                <deviceLocation>hangzhou</deviceLocation>
                <systemContact>Hikvision.China</systemContact>
                <model>DS-2CD2143G2-I</model>
                <serialNumber>DS-2CD2143G2-I20211203AAWRG12345678</serialNumber>
                <macAddress>98:8b:0a:04:d6:1e</macAddress>
                <firmwareVersion>V5.7.3</firmwareVersion>
                <firmwareReleasedDate>build 220112</firmwareReleasedDate>
                <deviceType>IPCamera</deviceType>
            </DeviceInfo>
            """;

    /**
     * Two streams of camera 1 plus an extra third stream: 101 main (VBR, fps
     * reported x100), 102 sub (CBR, audio disabled), 103 other.
     */
    static final String STREAMING_CHANNELS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <StreamingChannelList version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
                <StreamingChannel version="2.0">
                    <id>101</id>
                    <channelName>Camera 01</channelName>
                    <enabled>true</enabled>
                    <Video>
                        <enabled>true</enabled>
                        <videoInputChannelID>1</videoInputChannelID>
                        <videoCodecType>H.264</videoCodecType>
                        <videoResolutionWidth>2688</videoResolutionWidth>
                        <videoResolutionHeight>1520</videoResolutionHeight>
                        <videoQualityControlType>VBR</videoQualityControlType>
                        <fixedQuality>60</fixedQuality>
                        <vbrUpperCap>4096</vbrUpperCap>
                        <maxFrameRate>2500</maxFrameRate>
                        <GovLength>50</GovLength>
                        <H264Profile>Main</H264Profile>
                    </Video>
                    <Audio>
                        <enabled>true</enabled>
                        <audioInputChannelID>1</audioInputChannelID>
                        <audioCompressionType>G.711ulaw</audioCompressionType>
                    </Audio>
                </StreamingChannel>
                <StreamingChannel version="2.0">
                    <id>102</id>
                    <channelName>Camera 01</channelName>
                    <enabled>true</enabled>
                    <Video>
                        <enabled>true</enabled>
                        <videoInputChannelID>1</videoInputChannelID>
                        <videoCodecType>H.265</videoCodecType>
                        <videoResolutionWidth>640</videoResolutionWidth>
                        <videoResolutionHeight>480</videoResolutionHeight>
                        <videoQualityControlType>CBR</videoQualityControlType>
                        <constantBitRate>512</constantBitRate>
                        <maxFrameRate>1250</maxFrameRate>
                        <GovLength>25</GovLength>
                        <H265Profile>Main</H265Profile>
                    </Video>
                    <Audio>
                        <enabled>false</enabled>
                        <audioInputChannelID>1</audioInputChannelID>
                        <audioCompressionType>G.711ulaw</audioCompressionType>
                    </Audio>
                </StreamingChannel>
                <StreamingChannel version="2.0">
                    <id>103</id>
                    <channelName>Camera 01</channelName>
                    <enabled>false</enabled>
                    <Video>
                        <enabled>true</enabled>
                        <videoCodecType>MJPEG</videoCodecType>
                        <videoResolutionWidth>1280</videoResolutionWidth>
                        <videoResolutionHeight>720</videoResolutionHeight>
                        <videoQualityControlType>VBR</videoQualityControlType>
                        <vbrUpperCap>2048</vbrUpperCap>
                        <maxFrameRate>25</maxFrameRate>
                    </Video>
                </StreamingChannel>
            </StreamingChannelList>
            """;

    static final String NON_XML_LOGIN_PAGE = """
            <!DOCTYPE html>
            <html><head><title>index</title></head><body>Web admin UI</body></html>
            """;
}
