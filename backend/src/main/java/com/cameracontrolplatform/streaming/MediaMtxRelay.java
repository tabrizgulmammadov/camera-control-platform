package com.cameracontrolplatform.streaming;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Manages MediaMTX as a Docker-only WebRTC media plane. The backend registers
 * on-demand RTSP sources through the MediaMTX REST API and returns WHEP URLs
 * for browser playback. There is deliberately no executable or HLS fallback.
 */
@Service
public class MediaMtxRelay {

    private static final Logger log = LoggerFactory.getLogger(MediaMtxRelay.class);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(45);
    private static final Duration DOCKER_RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final String API_PATH = "/v3/config/global/get";

    static final String DOCKER_IMAGE = "bluenviron/mediamtx:latest";
    static final String CONTAINER_NAME = "cameracheck-mediamtx";
    private static final int ICE_PORT = 8189;

    private final int apiPort;
    private final int webrtcPort;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(API_TIMEOUT).build();
    private boolean dockerManaged;
    private volatile boolean available;

    public MediaMtxRelay(@Value("${cameracheck.mediamtx.api-port:9997}") int apiPort,
            @Value("${cameracheck.mediamtx.webrtc-port:8889}") int webrtcPort) {
        this.apiPort = apiPort;
        this.webrtcPort = webrtcPort;
    }

    @PostConstruct
    void start() {
        if (!startDocker()) {
            log.error("MediaMTX Docker container is unavailable; WebRTC streams cannot start. "
                    + "Start Docker Desktop and verify `docker info` succeeds.");
        }
    }

    private boolean startDocker() {
        try {
            String running = runDocker(Duration.ofSeconds(15),
                    "ps", "--filter", "name=" + CONTAINER_NAME, "--format", "{{.Names}}");
            boolean alreadyRunning = running != null && running.lines().anyMatch(CONTAINER_NAME::equals);
            if (alreadyRunning) {
                log.info("Adopting already-running MediaMTX container '{}'.", CONTAINER_NAME);
                dockerManaged = false;
            } else {
                log.info("Starting MediaMTX container '{}' from {}...", CONTAINER_NAME, DOCKER_IMAGE);
                String id = runDocker(DOCKER_RUN_TIMEOUT, dockerRunArgs());
                if (id == null) {
                    return false;
                }
                log.info("MediaMTX container started ({}).", id.trim());
                dockerManaged = true;
            }
            available = waitForApi();
            if (!available) {
                log.warn("MediaMTX API did not answer on 127.0.0.1:{} within {}s (docker logs {}).",
                        apiPort, STARTUP_WAIT.toSeconds(), CONTAINER_NAME);
                if (dockerManaged) {
                    stop();
                }
                return false;
            }
            log.info("MediaMTX up: WebRTC/WHEP on :{} and ICE on :{}.", webrtcPort, ICE_PORT);
            return true;
        } catch (IOException e) {
            log.warn("Docker CLI is not usable: {}", e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String[] dockerRunArgs() {
        List<String> args = new ArrayList<>(List.of(
                "run", "-d", "--rm", "--name", CONTAINER_NAME,
                "-p", "127.0.0.1:" + apiPort + ":" + apiPort,
                "-p", webrtcPort + ":" + webrtcPort,
                "-p", ICE_PORT + ":" + ICE_PORT + "/tcp",
                "-p", ICE_PORT + ":" + ICE_PORT + "/udp",
                "-e", "MTX_API=yes",
                "-e", "MTX_APIADDRESS=:" + apiPort,
                "-e", "MTX_WEBRTCADDRESS=:" + webrtcPort,
                "-e", "MTX_WEBRTCALLOWORIGIN=*",
                "-e", "MTX_WEBRTCADDITIONALHOSTS=127.0.0.1",
                "-e", "MTX_WEBRTCLOCALTCPADDRESS=:" + ICE_PORT,
                "-e", "MTX_WEBRTCLOCALUDPADDRESS=:" + ICE_PORT,
                "-e", "MTX_RTSP=no",
                "-e", "MTX_RTMP=no",
                "-e", "MTX_HLS=no",
                "-e", "MTX_SRT=no",
                "-e", "MTX_AUTHINTERNALUSERS_0_USER=any",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_0_ACTION=publish",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_1_ACTION=read",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_2_ACTION=playback",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_3_ACTION=api"));
        args.add(DOCKER_IMAGE);
        return args.toArray(String[]::new);
    }

    private String runDocker(Duration timeout, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("docker");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        var stderrFuture = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try {
                stderrFuture.complete(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                stderrFuture.complete("");
            }
        });
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.warn("docker {} timed out after {}s.", args[0], timeout.toSeconds());
            return null;
        }
        String stderr;
        try {
            stderr = stderrFuture.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            stderr = "";
        }
        if (process.exitValue() != 0) {
            log.warn("docker {} failed (exit {}): {}", args[0], process.exitValue(), stderr.trim());
            return null;
        }
        return stdout;
    }

    private boolean waitForApi() {
        long deadline = System.nanoTime() + STARTUP_WAIT.toNanos();
        URI apiUri = URI.create("http://127.0.0.1:" + apiPort + API_PATH);
        while (System.nanoTime() < deadline) {
            if (probeApi(http, apiUri, API_TIMEOUT)) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static boolean probeApi(HttpClient client, URI apiUri, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(apiUri)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean available() {
        return available;
    }

    /** Registers an on-demand RTSP source and returns its WHEP URL. */
    public String registerPath(String streamId, String credentialedRtspUrl) {
        if (!available()) {
            return null;
        }
        String pathName = pathName(streamId);
        String json = "{"
                + "\"source\":" + jsonString(credentialedRtspUrl) + ","
                + "\"sourceOnDemand\":true,"
                + "\"rtspTransport\":\"tcp\""
                + "}";
        try {
            HttpResponse<String> response = http.send(
                    apiRequest("POST", "/v3/config/paths/add/" + pathName, json),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("MediaMTX rejected path {} (HTTP {}: {}) for stream {}.", pathName,
                        response.statusCode(), StreamManager.maskCredentialsInText(response.body()), streamId);
                return null;
            }
            return whepUrl(streamId);
        } catch (IOException e) {
            log.warn("MediaMTX API unreachable for stream {}: {}", streamId, e.toString());
            available = false;
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void removePath(String streamId) {
        if (!available()) {
            return;
        }
        try {
            http.send(apiRequest("DELETE", "/v3/config/paths/delete/" + pathName(streamId), null),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.debug("Could not remove MediaMTX path {}: {}", pathName(streamId), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String whepUrl(String streamId) {
        return "http://localhost:" + webrtcPort + "/" + pathName(streamId) + "/whep";
    }

    private static String pathName(String streamId) {
        return "cam-" + streamId;
    }

    private HttpRequest apiRequest(String method, String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + path))
                .timeout(API_TIMEOUT)
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        return builder.build();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : c);
            }
        }
        return sb.append('"').toString();
    }

    @PreDestroy
    public void stop() {
        available = false;
        if (!dockerManaged) {
            return;
        }
        try {
            runDocker(Duration.ofSeconds(15), "stop", "-t", "2", CONTAINER_NAME);
        } catch (IOException e) {
            log.debug("Could not stop MediaMTX container: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dockerManaged = false;
    }
}
