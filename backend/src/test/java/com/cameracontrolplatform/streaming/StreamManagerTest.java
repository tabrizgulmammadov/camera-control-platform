package com.cameracontrolplatform.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.ErrorCode;

class StreamManagerTest {
    private MediaMtxRelay relay;
    private StreamManager manager;

    @BeforeEach
    void setUp() {
        relay = mock(MediaMtxRelay.class);
        manager = new StreamManager(relay);
    }

    @Test
    void injectsCredentialsIntoBareUrl() {
        assertEquals("rtsp://admin:pass@cam:554/live",
                StreamManager.injectCredentials("rtsp://cam:554/live", "admin", "pass"));
    }

    @Test
    void encodesSpecialCharactersIncludingSpaceAsPercent20() {
        assertEquals("rtsp://us%40er:p%40ss%3Aw%2Frd%20x@cam/live",
                StreamManager.injectCredentials("rtsp://cam/live", "us@er", "p@ss:w/rd x"));
    }

    @Test
    void startRejectsBlankAndNonRtspUrls() {
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(CameraException.class, () -> manager.start(null, null, null)).code());
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(CameraException.class, () -> manager.start("http://cam/live", null, null)).code());
    }

    @Test
    void startRequiresDockerRelay() {
        when(relay.available()).thenReturn(false);
        CameraException error = assertThrows(CameraException.class,
                () -> manager.start("rtsp://cam/live", null, null));
        assertEquals(ErrorCode.STREAM_ERROR, error.code());
    }

    @Test
    void startRegistersWebRtcPath() {
        when(relay.available()).thenReturn(true);
        when(relay.registerPath(anyString(), anyString())).thenReturn("http://localhost:8889/cam-x/whep");
        StreamSession session = manager.start("rtsp://cam/live", "admin", "pass");
        assertEquals("http://localhost:8889/cam-x/whep", session.whepUrl());
    }
}
