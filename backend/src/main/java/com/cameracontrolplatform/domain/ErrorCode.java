package com.cameracontrolplatform.domain;

/** Error codes shared with the frontend — see API-CONTRACT.md. */
public enum ErrorCode {
    ONVIF_NOT_ENABLED,
    ONVIF_AUTH_FAILED,
    DEVICE_UNREACHABLE,
    STREAM_ERROR,
    BAD_REQUEST,
    NOT_FOUND,
    INTERNAL
}
