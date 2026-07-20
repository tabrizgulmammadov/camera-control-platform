package com.cameracheck.domain;

/**
 * Genetec-style protocol driver abstraction. Each supported camera protocol
 * (ONVIF, vendor-native SDKs, ...) provides one implementation. Drivers are
 * discovered via the {@link CameraDriverRegistry} so new camera models can be
 * supported by adding a driver without touching the API layer.
 */
public interface CameraDriver {

    /** Unique protocol identifier, e.g. "onvif". */
    String protocol();

    /**
     * Opens a logical connection to the device. Implementations should be
     * cheap here and defer network I/O to the {@link CameraConnection} calls
     * where sensible.
     */
    CameraConnection connect(CameraEndpoint endpoint) throws CameraException;
}
