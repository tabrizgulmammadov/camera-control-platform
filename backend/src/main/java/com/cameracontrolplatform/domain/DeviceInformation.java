package com.cameracontrolplatform.domain;

public record DeviceInformation(
        String manufacturer,
        String model,
        String firmwareVersion,
        String serialNumber) {
}
