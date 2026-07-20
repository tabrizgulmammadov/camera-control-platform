package com.cameracheck.domain;

public record MediaProfile(
        String token,
        String name,
        StreamType streamType,
        VideoEncoderConfig videoEncoder,
        AudioEncoderConfig audioEncoder,
        String rtspUri) {

    public MediaProfile withStreamType(StreamType type) {
        return new MediaProfile(token, name, type, videoEncoder, audioEncoder, rtspUri);
    }

    public MediaProfile withRtspUri(String uri) {
        return new MediaProfile(token, name, streamType, videoEncoder, audioEncoder, uri);
    }
}
