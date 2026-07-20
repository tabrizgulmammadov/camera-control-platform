package com.cameracontrolplatform.driver.hikvision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cameracontrolplatform.domain.CameraConnection;
import com.cameracontrolplatform.domain.CameraDriver;
import com.cameracontrolplatform.domain.CameraEndpoint;

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
