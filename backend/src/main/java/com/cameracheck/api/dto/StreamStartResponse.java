package com.cameracheck.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response to POST /api/stream/start. Playback is always WebRTC through WHEP.
 */
public record StreamStartResponse(
        String streamId,
        @JsonInclude(JsonInclude.Include.ALWAYS) String whepUrl,
        String transport,
        Details details) {

    public record Details(String rtspUrl, String startedAt) {
    }
}
