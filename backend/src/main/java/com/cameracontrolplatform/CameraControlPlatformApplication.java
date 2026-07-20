package com.cameracontrolplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CameraControlPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(CameraControlPlatformApplication.class, args);
    }
}
