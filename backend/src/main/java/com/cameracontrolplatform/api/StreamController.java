package com.cameracontrolplatform.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cameracontrolplatform.api.dto.StreamDetailsResponse;
import com.cameracontrolplatform.api.dto.StreamStartRequest;
import com.cameracontrolplatform.api.dto.StreamStartResponse;
import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.ErrorCode;
import com.cameracontrolplatform.streaming.StreamManager;
import com.cameracontrolplatform.streaming.StreamSession;


@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final StreamManager streamManager;

    public StreamController(StreamManager streamManager) {
        this.streamManager = streamManager;
    }

    @PostMapping("/start")
    public StreamStartResponse start(@RequestBody StreamStartRequest request) {
        if (request == null || request.rtspUrl() == null || request.rtspUrl().isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "rtspUrl is required");
        }
        StreamSession session = streamManager.start(
                request.rtspUrl(), request.username(), request.password());

        return new StreamStartResponse(
                session.streamId(),
                session.whepUrl(),
                "WEBRTC",
                new StreamStartResponse.Details(session.rtspUrl(), session.startedAt().toString()));
    }

    @GetMapping("/{streamId}")
    public StreamDetailsResponse details(@PathVariable String streamId) {
        StreamSession session = streamManager.get(streamId);
        boolean relayAvailable = streamManager.relayAvailable();
        return new StreamDetailsResponse(
                session.streamId(),
                relayAvailable,
                session.rtspUrl(),
                session.startedAt().toString(),
                relayAvailable ? null : "MediaMTX Docker container is unavailable.");
    }

    @DeleteMapping("/{streamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String streamId) {
        streamManager.stop(streamId);
    }

}
