package com.cameracheck.streaming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** One running ffmpeg RTSP-to-HLS pipeline. */
public final class StreamSession {

    private static final int MAX_LOG_TAIL_LINES = 3;

    private final String streamId;
    private final String rtspUrl;
    private final Instant startedAt;
    private final Process process;
    private final Path directory;
    private final Path logFile;
    private final String whepUrl; // null when the MediaMTX relay is unavailable
    private final AtomicReference<Instant> lastAccessed;
    private volatile String failureReason; // cached once ffmpeg has died

    StreamSession(String streamId, String rtspUrl, Instant startedAt, Process process,
            Path directory, Path logFile, String whepUrl) {
        this.streamId = streamId;
        this.rtspUrl = rtspUrl;
        this.startedAt = startedAt;
        this.process = process;
        this.directory = directory;
        this.logFile = logFile;
        this.whepUrl = whepUrl;
        this.lastAccessed = new AtomicReference<>(startedAt);
    }

    public String streamId() {
        return streamId;
    }

    /** RTSP URL without injected credentials — safe to echo to clients. */
    public String rtspUrl() {
        return rtspUrl;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** WHEP playback URL on the MediaMTX relay, or null (HLS-only). */
    public String whepUrl() {
        return whepUrl;
    }

    public Path directory() {
        return directory;
    }

    Process process() {
        return process;
    }

    public boolean ffmpegAlive() {
        return process.isAlive();
    }

    /**
     * Human-readable cause of a dead ffmpeg process (exit code + tail of its
     * error log, with any credentials masked), or {@code null} while ffmpeg is
     * still running. Cached after first computation.
     */
    public String failureReason() {
        if (process.isAlive()) {
            return null;
        }
        String cached = failureReason;
        if (cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder("ffmpeg exited with code ").append(process.exitValue());
        String tail = logTail();
        if (tail != null && !tail.isBlank()) {
            sb.append(": ").append(tail);
        }
        String computed = sb.toString();
        failureReason = computed;
        return computed;
    }

    /** Last few non-blank log lines, credentials masked. Best effort. */
    private String logTail() {
        try {
            List<String> lines = Files.readAllLines(logFile).stream()
                    .filter(l -> !l.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                return null;
            }
            int from = Math.max(0, lines.size() - MAX_LOG_TAIL_LINES);
            return StreamManager.maskCredentialsInText(
                    String.join("; ", lines.subList(from, lines.size())).trim());
        } catch (IOException e) {
            return null;
        }
    }

    public Instant lastAccessed() {
        return lastAccessed.get();
    }

    public void touch() {
        lastAccessed.set(Instant.now());
    }
}
