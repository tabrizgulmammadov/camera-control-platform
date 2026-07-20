package com.cameracontrolplatform.domain;

public record AudioEncoderConfig(
        String encoding,
        Integer bitrateKbps,
        Integer sampleRateKhz) {
}
