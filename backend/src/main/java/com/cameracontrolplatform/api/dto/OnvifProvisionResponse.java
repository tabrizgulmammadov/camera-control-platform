package com.cameracontrolplatform.api.dto;

/** Response shape of POST /api/camera/onvif/provision — mirrors API-CONTRACT.md. */
public record OnvifProvisionResponse(String integrationStatus, String userStatus, String note) {
}
