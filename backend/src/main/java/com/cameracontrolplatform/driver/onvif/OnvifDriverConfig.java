package com.cameracontrolplatform.driver.onvif;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.cameracontrolplatform.domain.CameraDriverDescriptor;

/**
 * Registers the ONVIF driver: the driver bean itself is {@code @Lazy} (only
 * instantiated on first actual use), while the always-created lightweight
 * {@link CameraDriverDescriptor} advertises it to the registry.
 */
@Configuration
public class OnvifDriverConfig {

    @Bean
    @Lazy
    OnvifCameraDriver onvifCameraDriver() {
        return new OnvifCameraDriver();
    }

    @Bean
    CameraDriverDescriptor onvifDriverDescriptor(ObjectProvider<OnvifCameraDriver> driver) {
        return new CameraDriverDescriptor("ONVIF", "Generic ONVIF", false, driver::getObject);
    }
}
