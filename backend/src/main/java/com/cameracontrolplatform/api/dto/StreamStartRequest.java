package com.cameracontrolplatform.api.dto;

public record StreamStartRequest(String rtspUrl, String username, String password) {
}
