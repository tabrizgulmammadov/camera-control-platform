package com.cameracheck.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.cameracheck.api.dto.StreamDetailsResponse;
import com.cameracheck.api.dto.StreamStartRequest;
import com.cameracheck.api.dto.StreamStartResponse;
import com.cameracheck.domain.CameraException;
import com.cameracheck.domain.ErrorCode;
import com.cameracheck.streaming.StreamManager;
import com.cameracheck.streaming.StreamSession;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final StreamManager streamManager;

    public StreamController(StreamManager streamManager) {
        this.streamManager = streamManager;
    }

    @PostMapping("/start")
    public StreamStartResponse start(@RequestBody StreamStartRequest request, HttpServletRequest http) {
        if (request == null || request.rtspUrl() == null || request.rtspUrl().isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "rtspUrl is required");
        }
        StreamSession session = streamManager.start(
                request.rtspUrl(), request.username(), request.password());

        String hlsUrl = UriComponentsBuilder.fromUriString(baseUrl(http))
                .path("/hls/{id}/index.m3u8")
                .buildAndExpand(session.streamId())
                .toUriString();

        return new StreamStartResponse(
                session.streamId(),
                hlsUrl,
                session.whepUrl(),
                session.whepUrl() != null ? "WEBRTC" : "HLS",
                new StreamStartResponse.Details(session.rtspUrl(), session.startedAt().toString()));
    }

    @GetMapping("/{streamId}")
    public StreamDetailsResponse details(@PathVariable String streamId) {
        StreamSession session = streamManager.get(streamId);
        boolean alive = session.ffmpegAlive();
        return new StreamDetailsResponse(
                session.streamId(),
                alive,
                session.rtspUrl(),
                session.startedAt().toString(),
                alive,
                session.failureReason());
    }

    @DeleteMapping("/{streamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String streamId) {
        streamManager.stop(streamId);
    }

    private String baseUrl(HttpServletRequest http) {
        String host = http.getServerName();
        int port = http.getServerPort();
        return http.getScheme() + "://" + host + (port == 80 || port == 443 ? "" : ":" + port);
    }
}
