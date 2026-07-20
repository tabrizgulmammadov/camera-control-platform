package com.cameracheck.driver.onvif;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cameracheck.domain.CameraConnection;
import com.cameracheck.domain.CameraDriver;
import com.cameracheck.domain.CameraEndpoint;

/**
 * ONVIF Profile S driver: raw SOAP 1.2 over the JDK HttpClient with
 * WS-Security UsernameToken (password digest). Registered lazily via
 * {@link OnvifDriverConfig} under the id "ONVIF".
 */
public class OnvifCameraDriver implements CameraDriver {

    private static final Logger log = LoggerFactory.getLogger(OnvifCameraDriver.class);

    private final SoapClient soapClient = new SoapClient();

    public OnvifCameraDriver() {
        log.info("ONVIF driver instantiated (lazy first use)");
    }

    @Override
    public String protocol() {
        return "onvif";
    }

    @Override
    public CameraConnection connect(CameraEndpoint endpoint) {
        return new OnvifConnection(soapClient, endpoint);
    }
}
