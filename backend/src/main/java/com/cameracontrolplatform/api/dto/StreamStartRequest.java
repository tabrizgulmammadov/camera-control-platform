package com.cameracheck.api.dto;

public record StreamStartRequest(String rtspUrl, String username, String password) {
}
