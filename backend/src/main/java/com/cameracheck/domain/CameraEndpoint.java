package com.cameracheck.domain;

/**
 * Network address + credentials for a camera, protocol-agnostic.
 */
public record CameraEndpoint(String host, int port, String username, String password) {

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }
}
