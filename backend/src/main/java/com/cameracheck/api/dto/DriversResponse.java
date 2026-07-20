package com.cameracheck.api.dto;

import java.util.List;

/** Response shape of GET /api/camera/drivers — mirrors API-CONTRACT.md. */
public record DriversResponse(List<DriverDto> drivers) {

    public record DriverDto(String id, String displayName, boolean canProvisionOnvif) {
    }
}
