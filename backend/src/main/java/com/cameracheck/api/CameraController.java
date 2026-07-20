package com.cameracheck.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cameracheck.api.dto.CameraProfilesRequest;
import com.cameracheck.api.dto.CameraProfilesResponse;
import com.cameracheck.api.dto.DriversResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.cameracheck.api.dto.OnvifProvisionRequest;
import com.cameracheck.api.dto.OnvifProvisionResponse;
import com.cameracheck.api.dto.PtzContinuousRequest;
import com.cameracheck.domain.CameraDriverRegistry;

/**
 * Generic driver endpoints: driver discovery (never instantiates a driver —
 * descriptors only) and profile inspection for any registered driver.
 */
@RestController
@RequestMapping("/api/camera")
public class CameraController {

    private final CameraDriverRegistry drivers;
    private final CameraProfileService profileService;

    public CameraController(CameraDriverRegistry drivers, CameraProfileService profileService) {
        this.drivers = drivers;
        this.profileService = profileService;
    }

    @GetMapping("/drivers")
    public DriversResponse listDrivers() {
        return new DriversResponse(drivers.descriptors().stream()
                .map(d -> new DriversResponse.DriverDto(d.id(), d.displayName(), d.canProvisionOnvif()))
                .toList());
    }

    @PostMapping("/onvif/status")
    public java.util.Map<String, Object> onvifStatus(@RequestBody OnvifProvisionRequest request) {
        Boolean enabled = profileService.onvifStatus(
                request == null ? null : request.driver(),
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.username(),
                request == null ? null : request.password());
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("supported", enabled != null);
        body.put("onvifEnabled", enabled);
        return body;
    }

    @PostMapping("/onvif/provision")
    public OnvifProvisionResponse provisionOnvif(@RequestBody OnvifProvisionRequest request) {
        var result = profileService.provisionOnvif(
                request == null ? null : request.driver(),
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.username(),
                request == null ? null : request.password(),
                request == null ? null : request.onvifUsername(),
                request == null ? null : request.onvifPassword());
        return new OnvifProvisionResponse(
                result.integrationStatus(), result.userStatus(), result.note());
    }

    @PostMapping("/ptz/continuous")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ptzContinuous(@RequestBody PtzContinuousRequest request) {
        profileService.ptzContinuous(
                request == null ? null : request.driver(),
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.username(),
                request == null ? null : request.password(),
                request == null ? null : request.channel(),
                request == null ? null : request.pan(),
                request == null ? null : request.tilt());
    }

    @PostMapping("/profiles")
    public CameraProfilesResponse profiles(@RequestBody CameraProfilesRequest request) {
        return profileService.fetchProfiles(
                request == null ? null : request.driver(),
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.username(),
                request == null ? null : request.password());
    }
}
