package com.cameracontrolplatform.streaming;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** One MediaMTX WebRTC stream registration. */
public final class StreamSession {
    private final String streamId;
    private final String rtspUrl;
    private final Instant startedAt;
    private final String whepUrl;
    private final AtomicReference<Instant> lastAccessed;

    StreamSession(String streamId, String rtspUrl, Instant startedAt, String whepUrl) {
        this.streamId = streamId;
        this.rtspUrl = rtspUrl;
        this.startedAt = startedAt;
        this.whepUrl = whepUrl;
        this.lastAccessed = new AtomicReference<>(startedAt);
    }

    public String streamId() { return streamId; }
    public String rtspUrl() { return rtspUrl; }
    public Instant startedAt() { return startedAt; }
    public String whepUrl() { return whepUrl; }
    public Instant lastAccessed() { return lastAccessed.get(); }
    public void touch() { lastAccessed.set(Instant.now()); }
}
