package com.cameracheck.api.dto;

/**
 * Body of POST /api/camera/ptz/continuous. {@code pan}/{@code tilt} are speeds
 * in −100…100 (0 = stop that axis); {@code channel} defaults to 1.
 */
public record PtzContinuousRequest(
        String driver,
        String host,
        Integer port,
        String username,
        String password,
        Integer channel,
        Integer pan,
        Integer tilt) {
}
