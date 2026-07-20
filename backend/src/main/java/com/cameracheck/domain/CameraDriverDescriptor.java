package com.cameracheck.domain;

import java.util.function.Supplier;

/**
 * Lightweight driver metadata plus a lazy factory. Descriptors are cheap
 * always-instantiated beans; the {@code factory} resolves the (Spring-lazy)
 * {@link CameraDriver} bean only when a request actually needs the driver, so
 * listing available drivers never instantiates any of them.
 *
 * @param id                contract driver id, e.g. "ONVIF", "HIKVISION" (case-insensitive lookup)
 * @param displayName       human-readable name shown in the frontend driver picker
 * @param canProvisionOnvif whether this driver's connections implement {@link OnvifProvisioning}
 *                          (static metadata so the frontend can offer the flow without
 *                          instantiating the driver)
 * @param factory           resolves the driver instance on first use
 */
public record CameraDriverDescriptor(String id, String displayName, boolean canProvisionOnvif,
        Supplier<CameraDriver> factory) {
}
