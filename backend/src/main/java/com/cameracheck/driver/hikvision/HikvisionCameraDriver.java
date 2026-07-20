package com.cameracheck.driver.hikvision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cameracheck.domain.CameraConnection;
import com.cameracheck.domain.CameraDriver;
import com.cameracheck.domain.CameraEndpoint;

/**
 * Hikvision native driver: ISAPI REST/XML over HTTP with digest auth.
 * Registered lazily via {@link HikvisionDriverConfig} under the id
 * "HIKVISION".
 */
public class HikvisionCameraDriver implements CameraDriver {

    private static final Logger log = LoggerFactory.getLogger(HikvisionCameraDriver.class);

    private final IsapiClient client = new IsapiClient();

    public HikvisionCameraDriver() {
        log.info("Hikvision ISAPI driver instantiated (lazy first use)");
    }

    @Override
    public String protocol() {
        return "hikvision";
    }

    @Override
    public CameraConnection connect(CameraEndpoint endpoint) {
        return new HikvisionConnection(client, endpoint);
    }
}
