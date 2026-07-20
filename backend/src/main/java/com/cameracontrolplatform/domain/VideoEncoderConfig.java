package com.cameracontrolplatform.domain;

public record VideoEncoderConfig(
        String encoding,
        Resolution resolution,
        Integer frameRate,
        Integer bitrateKbps,
        Double quality,
        Integer govLength,
        String profile) {

    public record Resolution(int width, int height) {
    }
}
