package com.cameracheck.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response to POST /api/stream/start. {@code whepUrl} is present (and
 * {@code transport} is "WEBRTC") when the MediaMTX relay is available;
 * otherwise {@code whepUrl} is null and {@code transport} is "HLS".
 */
public record StreamStartResponse(
        String streamId,
        String hlsUrl,
        @JsonInclude(JsonInclude.Include.ALWAYS) String whepUrl,
        String transport,
        Details details) {

    public record Details(String rtspUrl, String startedAt) {
    }
}
