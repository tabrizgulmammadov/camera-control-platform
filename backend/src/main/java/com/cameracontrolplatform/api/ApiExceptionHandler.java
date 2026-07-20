package com.cameracheck.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.cameracheck.api.dto.ErrorResponse;
import com.cameracheck.domain.CameraException;
import com.cameracheck.domain.ErrorCode;

/** Maps every failure to the contract's error envelope { code, message }. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CameraException.class)
    public ResponseEntity<ErrorResponse> camera(CameraException e) {
        HttpStatus status = switch (e.code()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ONVIF_AUTH_FAILED -> HttpStatus.UNAUTHORIZED;
            case ONVIF_NOT_ENABLED, DEVICE_UNREACHABLE -> HttpStatus.BAD_GATEWAY;
            case STREAM_ERROR, INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> badBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), "Malformed JSON request body"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ErrorCode.NOT_FOUND.name(), "Not found: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.INTERNAL.name(), "Unexpected server error: " + e.getMessage()));
    }
}
