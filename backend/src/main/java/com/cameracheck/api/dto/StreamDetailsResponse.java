package com.cameracheck.api.dto;

public record StreamDetailsResponse(
        String streamId,
        boolean running,
        String rtspUrl,
        String startedAt,
        String error) {
}
