package com.cameracheck.domain;

public record AudioEncoderConfig(
        String encoding,
        Integer bitrateKbps,
        Integer sampleRateKhz) {
}
