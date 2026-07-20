package com.cameracontrolplatform.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.cameracontrolplatform.api.dto.ErrorResponse;
import com.cameracontrolplatform.domain.CameraDriverRegistry;
import com.cameracontrolplatform.domain.CameraException;
import com.cameracontrolplatform.domain.ErrorCode;
import com.cameracontrolplatform.streaming.StreamManager;

/** Every error code must map to the contracted HTTP status and envelope shape. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private StreamManager streamManager;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        streamManager = mock(StreamManager.class);
        CameraDriverRegistry registry = mock(CameraDriverRegistry.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new StreamController(streamManager),
                        new OnvifController(new CameraProfileService(registry)))
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void statusMappingPerErrorCode() {
        assertEquals(HttpStatus.BAD_REQUEST, statusFor(ErrorCode.BAD_REQUEST));
        assertEquals(HttpStatus.NOT_FOUND, statusFor(ErrorCode.NOT_FOUND));
        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(ErrorCode.ONVIF_AUTH_FAILED));
        assertEquals(HttpStatus.BAD_GATEWAY, statusFor(ErrorCode.ONVIF_NOT_ENABLED));
        assertEquals(HttpStatus.BAD_GATEWAY, statusFor(ErrorCode.DEVICE_UNREACHABLE));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, statusFor(ErrorCode.STREAM_ERROR));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, statusFor(ErrorCode.INTERNAL));
    }

    private HttpStatus statusFor(ErrorCode code) {
        ResponseEntity<ErrorResponse> res = handler.camera(new CameraException(code, "boom"));
        assertEquals(code.name(), res.getBody().code());
        assertEquals("boom", res.getBody().message());
        return HttpStatus.valueOf(res.getStatusCode().value());
    }

    @Test
    void unknownStreamIdReturns404WithNotFoundEnvelope() throws Exception {
        when(streamManager.get(anyString()))
                .thenThrow(new CameraException(ErrorCode.NOT_FOUND, "Unknown streamId: nope"));
        mvc.perform(get("/api/stream/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Unknown streamId: nope"));
    }

    @Test
    void missingHlsResourceReturns404NotFoundEnvelope() {
        ResponseEntity<ErrorResponse> res = handler.notFound(
                new NoResourceFoundException(HttpMethod.GET, "hls/dead/index.m3u8"));
        assertEquals(404, res.getStatusCode().value());
        assertEquals("NOT_FOUND", res.getBody().code());
        assertEquals("Not found: hls/dead/index.m3u8", res.getBody().message());
    }

    @Test
    void malformedJsonBodyReturns400Envelope() throws Exception {
        mvc.perform(post("/api/stream/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    void missingRtspUrlReturns400Envelope() throws Exception {
        mvc.perform(post("/api/stream/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void missingHostReturns400Envelope() throws Exception {
        mvc.perform(post("/api/onvif/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("host is required"));
    }

    @Test
    void unexpectedExceptionReturns500InternalEnvelope() throws Exception {
        when(streamManager.start(any(), any(), any())).thenThrow(new IllegalStateException("kaput"));
        mvc.perform(post("/api/stream/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rtspUrl\":\"rtsp://x/y\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"));
    }

    @Test
    void deleteIsIdempotent204EvenForUnknownStream() throws Exception {
        mvc.perform(delete("/api/stream/whatever")).andExpect(status().isNoContent());
    }
}
