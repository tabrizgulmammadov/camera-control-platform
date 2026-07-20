package com.cameracheck.domain;

public record DeviceInformation(
        String manufacturer,
        String model,
        String firmwareVersion,
        String serialNumber) {
}
