package com.cameracontrolplatform.streaming;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.ErrorCode;

import jakarta.annotation.PreDestroy;

/** Tracks WebRTC streams registered with the Docker MediaMTX relay. */
@Service
public class StreamManager {

    private static final Duration MAX_IDLE = Duration.ofMinutes(5);
    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();
    private final MediaMtxRelay relay;

    public StreamManager(MediaMtxRelay relay) {
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
        if (!relay.available()) {
            throw new CameraException(ErrorCode.STREAM_ERROR,
                    "MediaMTX Docker container is unavailable. Start Docker Desktop and retry.");
        }

        String streamId = newStreamId();
        String inputUrl = injectCredentials(trimmed, username, password);
        String whepUrl = relay.registerPath(streamId, inputUrl);
        if (whepUrl == null) {
            throw new CameraException(ErrorCode.STREAM_ERROR,
                    "Could not register the stream with the MediaMTX Docker container.");
        }

        StreamSession session = new StreamSession(streamId, trimmed, Instant.now(), whepUrl);
        sessions.put(streamId, session);
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

    public boolean relayAvailable() {
        return relay.available();
    }

    /** Idempotent: stopping an unknown/already-reaped stream is a no-op. */
    public void stop(String streamId) {
        StreamSession session = sessions.remove(streamId);
        if (session != null) {
            relay.removePath(session.streamId());
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void reapIdleStreams() {
        Instant cutoff = Instant.now().minus(MAX_IDLE);
        sessions.values().stream()
                .filter(s -> s.lastAccessed().isBefore(cutoff))
                .map(StreamSession::streamId)
                .toList()
                .forEach(this::stop);
    }

    @PreDestroy
    public void shutdown() {
        sessions.keySet().forEach(this::stop);
    }

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
            return rtspUrl;
        }
        String user = urlUserinfoEncode(username);
        String pass = password == null ? "" : urlUserinfoEncode(password);
        return rtspUrl.substring(0, schemeEnd + 3) + user + ":" + pass + "@" + rest;
    }

    private static String urlUserinfoEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

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
}
