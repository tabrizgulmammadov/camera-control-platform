package com.cameracheck.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cameracheck.domain.AudioEncoderConfig;
import com.cameracheck.domain.CameraConnection;
import com.cameracheck.domain.CameraDriver;
import com.cameracheck.domain.CameraDriverDescriptor;
import com.cameracheck.domain.CameraDriverRegistry;
import com.cameracheck.domain.CameraEndpoint;
import com.cameracheck.domain.DeviceInformation;
import com.cameracheck.domain.MediaProfile;
import com.cameracheck.domain.StreamType;
import com.cameracheck.domain.VideoEncoderConfig;

/**
 * Contract tests for GET /api/camera/drivers and POST /api/camera/profiles:
 * driver listing must not instantiate drivers (counted via the descriptor
 * factories), unknown driver ids give the 400 envelope, the driver field
 * defaults to ONVIF, and the legacy /api/onvif/profiles alias still works.
 */
class CameraControllerTest {

    private final AtomicInteger onvifInstantiations = new AtomicInteger();
    private final AtomicInteger hikvisionInstantiations = new AtomicInteger();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CameraDriverRegistry registry = new CameraDriverRegistry(List.of(
                new CameraDriverDescriptor("ONVIF", "Generic ONVIF", false, () -> {
                    onvifInstantiations.incrementAndGet();
                    return stubDriver("onvif", "Acme", "rtsp://cam/onvif-main");
                }),
                new CameraDriverDescriptor("HIKVISION", "Hikvision (ISAPI)", true, () -> {
                    hikvisionInstantiations.incrementAndGet();
                    return stubDriver("hikvision", "Hikvision", "rtsp://cam/hik-main");
                })));
        mvc = MockMvcBuilders
                .standaloneSetup(
                        new CameraController(registry, new CameraProfileService(registry)),
                        new OnvifController(new CameraProfileService(registry)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static CameraDriver stubDriver(String protocol, String manufacturer, String rtspUri) {
        return new CameraDriver() {
            @Override
            public String protocol() {
                return protocol;
            }

            @Override
            public CameraConnection connect(CameraEndpoint endpoint) {
                return new CameraConnection() {
                    @Override
                    public DeviceInformation getDeviceInformation() {
                        return new DeviceInformation(manufacturer, "M1", "1.0", "SN1");
                    }

                    @Override
                    public List<MediaProfile> getProfiles() {
                        return List.of(new MediaProfile("t1", "mainStream", StreamType.MAIN,
                                new VideoEncoderConfig("H264",
                                        new VideoEncoderConfig.Resolution(1920, 1080),
                                        25, 4096, 4.0, 50, "Main"),
                                new AudioEncoderConfig("AAC", 64, 8),
                                rtspUri));
                    }
                };
            }
        };
    }

    @Test
    void driversListMatchesContractAndDoesNotInstantiateDrivers() throws Exception {
        mvc.perform(get("/api/camera/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drivers.length()").value(2))
                .andExpect(jsonPath("$.drivers[?(@.id=='ONVIF')].displayName").value("Generic ONVIF"))
                .andExpect(jsonPath("$.drivers[?(@.id=='HIKVISION')].displayName").value("Hikvision (ISAPI)"));

        assertEquals(0, onvifInstantiations.get(), "listing drivers must not instantiate the ONVIF driver");
        assertEquals(0, hikvisionInstantiations.get(), "listing drivers must not instantiate the Hikvision driver");
    }

    @Test
    void provisioningUnsupportedDriverReturns400Envelope() throws Exception {
        mvc.perform(post("/api/camera/onvif/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driver\":\"ONVIF\",\"host\":\"1.2.3.4\","
                                + "\"onvifUsername\":\"u\",\"onvifPassword\":\"p\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("driver ONVIF does not support ONVIF provisioning"));
    }

    @Test
    void provisioningRequiresOnvifCredentials() throws Exception {
        mvc.perform(post("/api/camera/onvif/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driver\":\"HIKVISION\",\"host\":\"1.2.3.4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("onvifUsername and onvifPassword are required"));
    }

    @Test
    void driversListCarriesProvisioningCapability() throws Exception {
        mvc.perform(get("/api/camera/drivers"))
                .andExpect(jsonPath("$.drivers[?(@.id=='ONVIF')].canProvisionOnvif").value(false))
                .andExpect(jsonPath("$.drivers[?(@.id=='HIKVISION')].canProvisionOnvif").value(true));
    }

    @Test
    void profilesDefaultsToOnvifDriver() throws Exception {
        mvc.perform(post("/api/camera/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"1.2.3.4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceInfo.manufacturer").value("Acme"))
                .andExpect(jsonPath("$.profiles[0].streamType").value("MAIN"))
                .andExpect(jsonPath("$.profiles[0].rtspUri").value("rtsp://cam/onvif-main"));

        assertEquals(1, onvifInstantiations.get());
        assertEquals(0, hikvisionInstantiations.get(), "only the requested driver may be instantiated");
    }

    @Test
    void profilesSelectsDriverCaseInsensitively() throws Exception {
        mvc.perform(post("/api/camera/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driver\":\"hikvision\",\"host\":\"1.2.3.4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceInfo.manufacturer").value("Hikvision"))
                .andExpect(jsonPath("$.profiles[0].rtspUri").value("rtsp://cam/hik-main"));

        assertEquals(0, onvifInstantiations.get());
        assertEquals(1, hikvisionInstantiations.get());
    }

    @Test
    void unknownDriverReturns400Envelope() throws Exception {
        mvc.perform(post("/api/camera/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driver\":\"AXIS\",\"host\":\"1.2.3.4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertEquals(0, onvifInstantiations.get());
        assertEquals(0, hikvisionInstantiations.get());
    }

    @Test
    void missingHostReturns400Envelope() throws Exception {
        mvc.perform(post("/api/camera/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driver\":\"ONVIF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("host is required"));
    }

    @Test
    void legacyOnvifAliasDelegatesToOnvifDriver() throws Exception {
        mvc.perform(post("/api/onvif/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"1.2.3.4\",\"username\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceInfo.manufacturer").value("Acme"))
                .andExpect(jsonPath("$.profiles[0].name").value("mainStream"));

        assertEquals(1, onvifInstantiations.get());
        assertEquals(0, hikvisionInstantiations.get());
    }
}
