package com.cameracontrolplatform.api.dto;

/** The shared error envelope: { "code": "...", "message": "..." }. */
public record ErrorResponse(String code, String message) {
}
