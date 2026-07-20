package com.cameracontrolplatform.domain;

/**
 * Optional capability of a {@link CameraConnection}: use the vendor's native
 * management API to enable the ONVIF protocol on the device and create the
 * ONVIF user, so the device becomes usable through the generic ONVIF driver.
 * The API layer discovers support via {@code instanceof}; drivers that cannot
 * provision (including the ONVIF driver itself) simply don't implement it.
 */
public interface OnvifProvisioning {

    /**
     * Reads whether the ONVIF integration protocol is currently enabled on
     * the device (via the vendor management API, not by probing ONVIF).
     */
    boolean isOnvifEnabled();

    /**
     * Enables the ONVIF integration protocol and creates (or updates) the
     * given ONVIF user on the device.
     */
    Result provisionOnvif(String onvifUsername, String onvifPassword);

    record Result(String integrationStatus, String userStatus, String note) {
    }
}
