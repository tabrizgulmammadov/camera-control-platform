package com.cameracontrolplatform.driver.hikvision;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.cameracontrolplatform.domain.CameraDriverDescriptor;

/**
 * Registers the Hikvision ISAPI driver: lazy driver bean plus the
 * always-created lightweight descriptor for the registry.
 */
@Configuration
public class HikvisionDriverConfig {

    @Bean
    @Lazy
    HikvisionCameraDriver hikvisionCameraDriver() {
        return new HikvisionCameraDriver();
    }

    @Bean
    CameraDriverDescriptor hikvisionDriverDescriptor(ObjectProvider<HikvisionCameraDriver> driver) {
        return new CameraDriverDescriptor("HIKVISION", "Hikvision (ISAPI)", true, driver::getObject);
    }
}
