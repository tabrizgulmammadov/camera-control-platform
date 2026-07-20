package com.cameracheck.api.dto;

import java.util.List;

import com.cameracheck.domain.AudioEncoderConfig;
import com.cameracheck.domain.DeviceInformation;
import com.cameracheck.domain.MediaProfile;
import com.cameracheck.domain.VideoEncoderConfig;

/** Response shape of POST /api/camera/profiles (and the legacy /api/onvif/profiles alias) - mirrors API-CONTRACT.md. */
public record CameraProfilesResponse(DeviceInfoDto deviceInfo, List<ProfileDto> profiles) {

    public static CameraProfilesResponse of(DeviceInformation info, List<MediaProfile> profiles) {
        DeviceInfoDto infoDto = info == null ? null : new DeviceInfoDto(
                info.manufacturer(), info.model(), info.firmwareVersion(), info.serialNumber());
        List<ProfileDto> profileDtos = profiles.stream().map(ProfileDto::of).toList();
        return new CameraProfilesResponse(infoDto, profileDtos);
    }

    public record DeviceInfoDto(String manufacturer, String model, String firmwareVersion, String serialNumber) {
    }

    public record ProfileDto(
            String token,
            String name,
            String streamType,
            VideoEncoderDto videoEncoder,
            AudioEncoderDto audioEncoder,
            String rtspUri) {

        static ProfileDto of(MediaProfile p) {
            return new ProfileDto(
                    p.token(), p.name(), p.streamType().name(),
                    VideoEncoderDto.of(p.videoEncoder()),
                    AudioEncoderDto.of(p.audioEncoder()),
                    p.rtspUri());
        }
    }

    public record VideoEncoderDto(
            String encoding,
            ResolutionDto resolution,
            Integer frameRate,
            Integer bitrateKbps,
            Double quality,
            Integer govLength,
            String profile) {

        static VideoEncoderDto of(VideoEncoderConfig v) {
            if (v == null) {
                return null;
            }
            ResolutionDto res = v.resolution() == null ? null
                    : new ResolutionDto(v.resolution().width(), v.resolution().height());
            return new VideoEncoderDto(v.encoding(), res, v.frameRate(), v.bitrateKbps(),
                    v.quality(), v.govLength(), v.profile());
        }
    }

    public record ResolutionDto(int width, int height) {
    }

    public record AudioEncoderDto(String encoding, Integer bitrateKbps, Integer sampleRateKhz) {

        static AudioEncoderDto of(AudioEncoderConfig a) {
            if (a == null) {
                return null;
            }
            return new AudioEncoderDto(a.encoding(), a.bitrateKbps(), a.sampleRateKhz());
        }
    }
}
