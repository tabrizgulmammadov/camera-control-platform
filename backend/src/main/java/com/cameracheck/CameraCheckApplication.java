package com.cameracheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CameraCheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(CameraCheckApplication.class, args);
    }
}
