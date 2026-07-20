package com.cameracontrolplatform.domain;

/** Domain exception carrying a contract error code. */
public class CameraException extends RuntimeException {

    private final ErrorCode code;

    public CameraException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public CameraException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
