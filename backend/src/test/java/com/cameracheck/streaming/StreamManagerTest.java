package com.cameracheck.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.cameracheck.domain.CameraException;
import com.cameracheck.domain.ErrorCode;

class StreamManagerTest {

    @TempDir
    Path tmp;

    private StreamManager manager;

    @BeforeEach
    void setUp() throws IOException {
        HlsStorage storage = mock(HlsStorage.class);
        Path root = Files.createDirectories(tmp.resolve("hls"));
        Path logs = Files.createDirectories(tmp.resolve("logs"));
        when(storage.root()).thenReturn(root);
        when(storage.logsRoot()).thenReturn(logs);
        manager = new StreamManager(storage);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    // ---- credential injection ---------------------------------------------

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
    void leavesUrlAloneWhenCredentialsAlreadyEmbedded() {
        assertEquals("rtsp://a:b@cam/live",
                StreamManager.injectCredentials("rtsp://a:b@cam/live", "admin", "pass"));
    }

    @Test
    void leavesUrlAloneWithoutUsername() {
        assertEquals("rtsp://cam/live", StreamManager.injectCredentials("rtsp://cam/live", null, "x"));
        assertEquals("rtsp://cam/live", StreamManager.injectCredentials("rtsp://cam/live", " ", "x"));
    }

    @Test
    void nullPasswordBecomesEmpty() {
        assertEquals("rtsp://admin:@cam/live",
                StreamManager.injectCredentials("rtsp://cam/live", "admin", null));
    }

    @Test
    void atSignInPathDoesNotCountAsCredentials() {
        assertEquals("rtsp://admin:p@cam/live@2",
                StreamManager.injectCredentials("rtsp://cam/live@2", "admin", "p"));
    }

    // ---- credential masking -----------------------------------------------

    @Test
    void maskCredentialsNeverLeaksPassword() {
        assertEquals("rtsp://***:***@cam:554/live",
                StreamManager.maskCredentials("rtsp://admin:sup3r$ecret@cam:554/live"));
        assertEquals("rtsp://cam:554/live", StreamManager.maskCredentials("rtsp://cam:554/live"));
        assertNull(StreamManager.maskCredentials(null));
        assertFalse(StreamManager.maskCredentials("rtsp://u:top%20secret@cam/x").contains("secret"));
    }

    @Test
    void maskCredentialsInTextMasksUrlsMidSentence() {
        String masked = StreamManager.maskCredentialsInText(
                "[in#0] Error opening input file rtsp://admin:hunter2@cam:554/live. Retrying rtsp://admin:hunter2@cam:554/live now");
        assertEquals("[in#0] Error opening input file rtsp://***:***@cam:554/live. Retrying rtsp://***:***@cam:554/live now",
                masked);
        assertNull(StreamManager.maskCredentialsInText(null));
    }

    // ---- lifecycle ---------------------------------------------------------

    @Test
    void unknownStreamIdThrowsNotFound() {
        CameraException e = assertThrows(CameraException.class, () -> manager.get("doesnotexist"));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
    }

    @Test
    void stopIsIdempotentForUnknownId() {
        manager.stop("doesnotexist"); // must not throw
        manager.stop("doesnotexist");
    }

    @Test
    void startRejectsBlankAndNonRtspUrls() {
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(CameraException.class, () -> manager.start(null, null, null)).code());
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(CameraException.class, () -> manager.start("  ", null, null)).code());
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(CameraException.class, () -> manager.start("http://cam/live", null, null)).code());
    }

    /**
     * Early-death detection (requires ffmpeg on PATH): pointing ffmpeg at a
     * closed port makes it exit quickly; the session must then report a
     * failure reason with exit code and log tail, with credentials masked.
     */
    @Test
    void deadFfmpegYieldsFailureReasonWithMaskedCredentials() throws Exception {
        StreamSession session = manager.start("rtsp://127.0.0.1:9/nowhere", "admin", "s3cret");
        boolean exited = session.process().waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(exited, "ffmpeg should die quickly on a refused RTSP connection");

        String reason = session.failureReason();
        assertNotNull(reason);
        assertTrue(reason.startsWith("ffmpeg exited with code "), reason);
        assertFalse(reason.contains("s3cret"), "failure reason must never leak the password: " + reason);
        assertFalse(session.ffmpegAlive());

        // stop() afterwards cleans up and stays idempotent
        manager.stop(session.streamId());
        manager.stop(session.streamId());
        assertThrows(CameraException.class, () -> manager.get(session.streamId()));
    }

    @Test
    void getReturnsSessionAndTouchesIt() {
        StreamSession session = manager.start("rtsp://127.0.0.1:9/nowhere", null, null);
        Instant before = session.lastAccessed();
        StreamSession fetched = manager.get(session.streamId());
        assertEquals(session.streamId(), fetched.streamId());
        assertEquals("rtsp://127.0.0.1:9/nowhere", fetched.rtspUrl());
        assertFalse(fetched.lastAccessed().isBefore(before));
        assertTrue(Duration.between(session.startedAt(), Instant.now()).toMinutes() < 1);
    }
}
