package com.cameracontrolplatform.api.dto;

public record OnvifProfilesRequest(String host, Integer port, String username, String password) {
}
