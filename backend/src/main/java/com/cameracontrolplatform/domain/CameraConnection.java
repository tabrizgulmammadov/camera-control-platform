package com.cameracheck.domain;

import java.util.List;

/**
 * A live, protocol-agnostic session with a camera. The API layer only ever
 * talks to this interface — never to protocol specifics.
 */
public interface CameraConnection extends AutoCloseable {

    DeviceInformation getDeviceInformation() throws CameraException;

    List<MediaProfile> getProfiles() throws CameraException;

    @Override
    default void close() {
        // stateless drivers need no teardown
    }
}
