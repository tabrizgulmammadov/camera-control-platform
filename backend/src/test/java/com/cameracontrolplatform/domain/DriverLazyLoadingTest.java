package com.cameracontrolplatform.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.cameracontrolplatform.driver.hikvision.HikvisionCameraDriver;
import com.cameracontrolplatform.driver.hikvision.HikvisionDriverConfig;
import com.cameracontrolplatform.driver.onvif.OnvifCameraDriver;
import com.cameracontrolplatform.driver.onvif.OnvifDriverConfig;

/**
 * Verifies the Spring wiring itself: driver beans are @Lazy, so context
 * startup and descriptor listing create no driver instance; only
 * forProtocol() triggers instantiation — and only of the requested driver.
 */
class DriverLazyLoadingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OnvifDriverConfig.class, HikvisionDriverConfig.class,
                    CameraDriverRegistry.class);

    @Test
    void contextStartupAndDescriptorListingInstantiateNoDriver() {
        runner.run(context -> {
            CameraDriverRegistry registry = context.getBean(CameraDriverRegistry.class);
            ConfigurableListableBeanFactory beans =
                    context.getSourceApplicationContext().getBeanFactory();

            assertFalse(beans.containsSingleton("onvifCameraDriver"),
                    "ONVIF driver must not be instantiated at startup");
            assertFalse(beans.containsSingleton("hikvisionCameraDriver"),
                    "Hikvision driver must not be instantiated at startup");

            assertEquals(2, registry.descriptors().size());
            assertFalse(beans.containsSingleton("onvifCameraDriver"),
                    "listing descriptors must not instantiate drivers");
            assertFalse(beans.containsSingleton("hikvisionCameraDriver"),
                    "listing descriptors must not instantiate drivers");
        });
    }

    @Test
    void forProtocolInstantiatesOnlyTheRequestedDriver() {
        runner.run(context -> {
            CameraDriverRegistry registry = context.getBean(CameraDriverRegistry.class);
            ConfigurableListableBeanFactory beans =
                    context.getSourceApplicationContext().getBeanFactory();

            CameraDriver driver = registry.forProtocol("HIKVISION");
            assertTrue(driver instanceof HikvisionCameraDriver);
            assertTrue(beans.containsSingleton("hikvisionCameraDriver"),
                    "requested driver must now exist as a singleton");
            assertFalse(beans.containsSingleton("onvifCameraDriver"),
                    "the other driver must stay uninstantiated");

            // Case-insensitive id, and repeated lookups reuse the same bean.
            assertTrue(registry.forProtocol("onvif") instanceof OnvifCameraDriver);
            assertEquals(registry.forProtocol("ONVIF"), registry.forProtocol("onvif"));
        });
    }
}
