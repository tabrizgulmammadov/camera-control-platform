package com.cameracheck.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cameracheck.api.dto.CameraProfilesResponse;
import com.cameracheck.api.dto.OnvifProfilesRequest;

/**
 * Legacy alias kept per API-CONTRACT.md: POST /api/onvif/profiles behaves
 * exactly like POST /api/camera/profiles with driver=ONVIF.
 */
@RestController
@RequestMapping("/api/onvif")
public class OnvifController {

    private final CameraProfileService profileService;

    public OnvifController(CameraProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping("/profiles")
    public CameraProfilesResponse profiles(@RequestBody OnvifProfilesRequest request) {
        return profileService.fetchProfiles(CameraProfileService.DEFAULT_DRIVER,
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.username(),
                request == null ? null : request.password());
    }
}
