package com.cameracheck.api.dto;

/** Body of POST /api/camera/profiles — driver defaults to "ONVIF" when omitted. */
public record CameraProfilesRequest(String driver, String host, Integer port, String username, String password) {
}
