package com.cameracheck.streaming;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cameracheck.domain.CameraException;
import com.cameracheck.domain.ErrorCode;

import jakarta.annotation.PreDestroy;

/**
 * Spawns one ffmpeg process per stream, remuxing RTSP (TCP interleaved) to a
 * short rolling HLS playlist in a per-stream temp directory. The directory is
 * served statically at /hls/{streamId}/** (see WebConfig). A scheduled reaper
 * kills sessions idle for more than 5 minutes.
 */
@Service
public class StreamManager {

    private static final Logger log = LoggerFactory.getLogger(StreamManager.class);
    private static final Duration MAX_IDLE = Duration.ofMinutes(5);
    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();
    private final Path hlsRoot;
    private final Path logsRoot;
    private final MediaMtxRelay relay;

    public StreamManager(HlsStorage storage, MediaMtxRelay relay) {
        this.hlsRoot = storage.root();
        this.logsRoot = storage.logsRoot();
        this.relay = relay;
    }

    public StreamSession start(String rtspUrl, String username, String password) {
        if (rtspUrl == null || rtspUrl.isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "rtspUrl is required");
        }
        String trimmed = rtspUrl.trim();
        if (!trimmed.regionMatches(true, 0, "rtsp://", 0, 7)) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "rtspUrl must start with rtsp://");
        }
        requireFfmpeg();

        String streamId = newStreamId();
        Path dir = hlsRoot.resolve(streamId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new CameraException(ErrorCode.STREAM_ERROR, "Could not create HLS directory", e);
        }

        String inputUrl = injectCredentials(trimmed, username, password);
        // ffmpeg output goes OUTSIDE the HTTP-served HLS tree: on failure ffmpeg
        // echoes the input URL — including credentials — into its log.
        Path logFile = logsRoot.resolve(streamId + ".log");
        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg",
                "-nostdin",
                "-loglevel", "error",
                "-rtsp_transport", "tcp",
                // Socket connect/IO timeout in microseconds. Without it ffmpeg
                // hangs for minutes on filtered ports / stalled cameras and the
                // client would only ever see a silent playlist 404.
                "-timeout", "8000000",
                // Some cameras (e.g. Hikvision) send B-frame packets without a
                // DTS; the MPEG-TS muxer rejects those in -c copy mode ("first
                // pts and dts value must be set"). genpts fills missing PTS,
                // the setts filter derives DTS from PTS when absent (verified
                // against a real Hikvision), and negative timestamps are
                // shifted to zero.
                "-fflags", "+genpts",
                "-i", inputUrl,
                "-c", "copy",
                "-an",
                "-bsf:v", "setts=dts=if(eq(DTS\\,NOPTS)\\,PTS\\,DTS)",
                "-avoid_negative_ts", "make_zero",
                "-f", "hls",
                "-hls_time", "1",
                "-hls_list_size", "5",
                "-hls_flags", "delete_segments",
                dir.resolve("index.m3u8").toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            deleteQuietly(dir);
            throw new CameraException(ErrorCode.STREAM_ERROR, "ffmpeg not found on PATH", e);
        }

        // Low-latency plane: register the source with the MediaMTX relay when it
        // is up. Best-effort by design — any relay problem degrades to HLS.
        String whepUrl = relay.registerPath(streamId, inputUrl);

        StreamSession session = new StreamSession(
                streamId, trimmed, Instant.now(), process, dir, logFile, whepUrl);
        sessions.put(streamId, session);
        log.info("Started stream {} for {} (transport {})",
                streamId, maskCredentials(trimmed), whepUrl != null ? "WEBRTC" : "HLS");
        return session;
    }

    public StreamSession get(String streamId) {
        StreamSession session = sessions.get(streamId);
        if (session == null) {
            throw new CameraException(ErrorCode.NOT_FOUND, "Unknown streamId: " + streamId);
        }
        session.touch();
        return session;
    }

    /** Idempotent: stopping an unknown/already-reaped stream is a no-op. */
    public void stop(String streamId) {
        StreamSession session = sessions.remove(streamId);
        if (session != null) {
            kill(session);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void reapIdleStreams() {
        Instant cutoff = Instant.now().minus(MAX_IDLE);
        sessions.values().stream()
                .filter(s -> s.lastAccessed().isBefore(cutoff))
                .toList()
                .forEach(s -> {
                    log.info("Reaping idle stream {}", s.streamId());
                    sessions.remove(s.streamId());
                    kill(s);
                });
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(this::kill);
        sessions.clear();
    }

    private void kill(StreamSession session) {
        relay.removePath(session.streamId());
        Process process = session.process();
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteQuietly(session.directory());
        try {
            Files.deleteIfExists(logsRoot.resolve(session.streamId() + ".log"));
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void requireFfmpeg() {
        try {
            Process probe = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().close();
            probe.waitFor(5, TimeUnit.SECONDS);
            probe.destroyForcibly();
        } catch (IOException e) {
            throw new CameraException(ErrorCode.STREAM_ERROR, "ffmpeg not found on PATH", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CameraException(ErrorCode.INTERNAL, "Interrupted while probing ffmpeg", e);
        }
    }

    /** Puts user:pass into the RTSP URL unless it already carries credentials. */
    static String injectCredentials(String rtspUrl, String username, String password) {
        if (username == null || username.isBlank()) {
            return rtspUrl;
        }
        int schemeEnd = rtspUrl.indexOf("://");
        if (schemeEnd < 0) {
            return rtspUrl;
        }
        String rest = rtspUrl.substring(schemeEnd + 3);
        int firstSlash = rest.indexOf('/');
        String authority = firstSlash >= 0 ? rest.substring(0, firstSlash) : rest;
        if (authority.contains("@")) {
            return rtspUrl; // already has embedded credentials
        }
        String user = urlUserinfoEncode(username);
        String pass = password == null ? "" : urlUserinfoEncode(password);
        return rtspUrl.substring(0, schemeEnd + 3) + user + ":" + pass + "@" + rest;
    }

    /**
     * Percent-encoding for the userinfo URI component. URLEncoder is
     * form-encoding: it turns a space into '+', which RTSP clients would take
     * literally — so rewrite '+' to '%20'.
     */
    private static String urlUserinfoEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Replaces "user:pass@" userinfo in a URL with "***:***@" for safe logging. */
    static String maskCredentials(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceFirst("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]+@", "$1***:***@");
    }

    /**
     * Masks "user:pass@" userinfo of every URL embedded anywhere in free text
     * (ffmpeg log lines echo the credentialed input URL mid-sentence).
     */
    static String maskCredentialsInText(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s]+@", "$1***:***@");
    }

    private String newStreamId() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // segments may still be locked briefly on Windows
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // best effort cleanup
        }
    }
}
