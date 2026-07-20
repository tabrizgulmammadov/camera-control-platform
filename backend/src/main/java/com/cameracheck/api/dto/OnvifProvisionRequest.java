package com.cameracheck.api.dto;

/** Request shape of POST /api/camera/onvif/provision — mirrors API-CONTRACT.md. */
public record OnvifProvisionRequest(
        String driver,
        String host,
        Integer port,
        String username,
        String password,
        String onvifUsername,
        String onvifPassword) {
}
