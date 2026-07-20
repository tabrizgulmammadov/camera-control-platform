package com.cameracheck.api;

import java.util.List;

import org.springframework.stereotype.Service;

import com.cameracheck.api.dto.CameraProfilesResponse;
import com.cameracheck.domain.CameraConnection;
import com.cameracheck.domain.CameraDriverRegistry;
import com.cameracheck.domain.CameraEndpoint;
import com.cameracheck.domain.CameraException;
import com.cameracheck.domain.DeviceInformation;
import com.cameracheck.domain.ErrorCode;
import com.cameracheck.domain.MediaProfile;
import com.cameracheck.domain.OnvifProvisioning;
import com.cameracheck.domain.PtzControl;

/**
 * Application service at the HTTP boundary: request validation plus the
 * driver-agnostic inspect flow (resolve driver → connect → device info +
 * profiles → DTO). Controllers stay thin and driver-unaware.
 */
@Service
public class CameraProfileService {

    static final String DEFAULT_DRIVER = "ONVIF";
    private static final int DEFAULT_PORT = 80;

    private final CameraDriverRegistry drivers;

    public CameraProfileService(CameraDriverRegistry drivers) {
        this.drivers = drivers;
    }

    public CameraProfilesResponse fetchProfiles(String driverIdOrNull, String host,
            Integer portOrNull, String username, String password) {
        String driverId = driverIdOrNull == null || driverIdOrNull.isBlank()
                ? DEFAULT_DRIVER
                : driverIdOrNull;
        CameraEndpoint endpoint = validatedEndpoint(host, portOrNull, username, password);

        try (CameraConnection connection = drivers.forProtocol(driverId).connect(endpoint)) {
            DeviceInformation info = connection.getDeviceInformation();
            List<MediaProfile> profiles = connection.getProfiles();
            return CameraProfilesResponse.of(info, profiles);
        }
    }

    /**
     * Reads whether ONVIF is enabled on the device via the vendor driver's
     * management API. Null when the driver cannot tell (no provisioning
     * capability).
     */
    public Boolean onvifStatus(String driverId, String host, Integer portOrNull,
            String username, String password) {
        if (driverId == null || driverId.isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "driver is required for ONVIF status");
        }
        CameraEndpoint endpoint = validatedEndpoint(host, portOrNull, username, password);
        try (CameraConnection connection = drivers.forProtocol(driverId).connect(endpoint)) {
            if (!(connection instanceof OnvifProvisioning provisioning)) {
                return null;
            }
            return provisioning.isOnvifEnabled();
        }
    }

    /**
     * Enables ONVIF + creates the ONVIF user on the device through the vendor
     * driver's management API. Requires an explicit driver id (there is no
     * sensible default — the ONVIF driver itself cannot provision ONVIF).
     */
    public OnvifProvisioning.Result provisionOnvif(String driverId, String host, Integer portOrNull,
            String username, String password, String onvifUsername, String onvifPassword) {
        if (driverId == null || driverId.isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "driver is required for ONVIF provisioning");
        }
        if (onvifUsername == null || onvifUsername.isBlank()
                || onvifPassword == null || onvifPassword.isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST,
                    "onvifUsername and onvifPassword are required");
        }
        CameraEndpoint endpoint = validatedEndpoint(host, portOrNull, username, password);

        try (CameraConnection connection = drivers.forProtocol(driverId).connect(endpoint)) {
            if (!(connection instanceof OnvifProvisioning provisioning)) {
                throw new CameraException(ErrorCode.BAD_REQUEST,
                        "driver " + driverId + " does not support ONVIF provisioning");
            }
            return provisioning.provisionOnvif(onvifUsername.trim(), onvifPassword);
        }
    }

    /**
     * Continuous PTZ move through the driver's {@link PtzControl} capability.
     * Speeds are −100…100 (0/0 = stop); channel defaults to 1.
     */
    public void ptzContinuous(String driverId, String host, Integer portOrNull,
            String username, String password, Integer channelOrNull, Integer pan, Integer tilt) {
        if (driverId == null || driverId.isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "driver is required for PTZ control");
        }
        if (pan == null || tilt == null) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "pan and tilt are required");
        }
        int channel = channelOrNull == null ? 1 : channelOrNull;
        if (channel < 1) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "channel must be >= 1");
        }
        CameraEndpoint endpoint = validatedEndpoint(host, portOrNull, username, password);

        try (CameraConnection connection = drivers.forProtocol(driverId).connect(endpoint)) {
            if (!(connection instanceof PtzControl ptz)) {
                throw new CameraException(ErrorCode.BAD_REQUEST,
                        "driver " + driverId + " does not support PTZ");
            }
            ptz.continuousMove(channel, pan, tilt);
        }
    }

    private static CameraEndpoint validatedEndpoint(String host, Integer portOrNull,
            String username, String password) {
        if (host == null || host.isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "host is required");
        }
        int port = portOrNull == null ? DEFAULT_PORT : portOrNull;
        if (port < 1 || port > 65535) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "port must be between 1 and 65535");
        }
        return new CameraEndpoint(host.trim(), port, username, password);
    }
}
