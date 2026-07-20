package com.cameracheck.streaming;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Manages MediaMTX as the low-latency media plane: the backend (control plane)
 * starts/adopts a MediaMTX instance and per stream registers an on-demand RTSP
 * source path via the MediaMTX REST API. Browsers then play the stream over
 * WebRTC using standard WHEP at {@code http://localhost:8889/cam-{id}/whep}.
 *
 * <p>Launch modes ({@code cameracheck.mediamtx.mode}):
 * <ul>
 *   <li>{@code docker} (default) — runs the official
 *       {@code bluenviron/mediamtx} image as container
 *       {@code cameracheck-mediamtx} ({@code docker run -d --rm}), configured
 *       purely via {@code MTX_*} env vars. Only the control API (loopback,
 *       9997), WHEP (8889) and the single ICE port (8189/tcp+udp) are
 *       published. Behind Docker Desktop NAT the container's own UDP host
 *       candidates are unreachable from the browser, so
 *       {@code MTX_WEBRTCADDITIONALHOSTS=127.0.0.1} plus ICE over the
 *       published 8189 (TCP and UDP) is what makes candidates actually
 *       connect. A container with that name already running is adopted. If the
 *       docker CLI is missing/broken, falls back to exe mode.</li>
 *   <li>{@code exe} — starts the bare exe
 *       ({@code cameracheck.mediamtx.path}, default
 *       {@code ..\.tools\mediamtx\mediamtx.exe}) as a managed child process
 *       with a backend-generated config written to the temp dir; output goes
 *       to the logs root (outside the HTTP-served tree — MediaMTX logs echo
 *       credentialed source URLs).</li>
 *   <li>{@code off} — HLS-only.</li>
 * </ul>
 *
 * <p>In every mode MediaMTX's own RTSP/RTMP/HLS/SRT server listeners are
 * disabled so nothing collides with the backend or cameras — RTSP is used
 * purely as a client-side <em>source</em> protocol, which does not need the
 * RTSP server. {@code @PreDestroy} stops whatever was started.
 *
 * <p>If MediaMTX cannot be brought up, {@link #available()} is false and the
 * system degrades to the ffmpeg HLS pipeline — a MediaMTX problem must never
 * fail a stream start.
 */
@Service
public class MediaMtxRelay {

    private static final Logger log = LoggerFactory.getLogger(MediaMtxRelay.class);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(15);
    private static final Duration DOCKER_RUN_TIMEOUT = Duration.ofMinutes(5); // first run pulls the image

    static final String DOCKER_IMAGE = "bluenviron/mediamtx:latest";
    static final String CONTAINER_NAME = "cameracheck-mediamtx";
    private static final int ICE_PORT = 8189;

    private final String mode;
    private final Path exePath;
    private final int apiPort;
    private final int webrtcPort;
    private final Path logsRoot;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(API_TIMEOUT)
            .build();

    private Process exeProcess;      // exe mode only
    private Path configFile;         // exe mode only
    private boolean dockerManaged;   // docker mode: stop the container on shutdown
    private volatile boolean available;

    public MediaMtxRelay(HlsStorage storage,
            @Value("${cameracheck.mediamtx.mode:docker}") String mode,
            @Value("${cameracheck.mediamtx.path:}") String configuredPath,
            @Value("${cameracheck.mediamtx.api-port:9997}") int apiPort,
            @Value("${cameracheck.mediamtx.webrtc-port:8889}") int webrtcPort) {
        this.logsRoot = storage.logsRoot();
        this.mode = mode == null ? "docker" : mode.trim().toLowerCase();
        this.apiPort = apiPort;
        this.webrtcPort = webrtcPort;
        this.exePath = configuredPath != null && !configuredPath.isBlank()
                ? Path.of(configuredPath)
                : defaultExePath();
    }

    /** {@code {projectRoot}/.tools/mediamtx/mediamtx.exe}, resolved from the working dir. */
    private static Path defaultExePath() {
        // Backend runs from backend/, the tools live one level up.
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd.resolve(".tools/mediamtx/mediamtx.exe");
        if (Files.exists(candidate)) {
            return candidate;
        }
        return cwd.getParent() != null
                ? cwd.getParent().resolve(".tools/mediamtx/mediamtx.exe")
                : candidate;
    }

    @PostConstruct
    void start() {
        switch (mode) {
            case "off" -> log.info("MediaMTX disabled (cameracheck.mediamtx.mode=off) — HLS-only mode.");
            case "exe" -> startExe();
            case "docker" -> {
                if (!startDocker()) {
                    log.warn("MediaMTX docker mode failed — trying exe fallback.");
                    startExe();
                }
            }
            default -> log.warn("Unknown cameracheck.mediamtx.mode '{}' (expected docker|exe|off) "
                    + "— HLS-only mode.", mode);
        }
    }

    // ---- docker mode -------------------------------------------------------

    /** Adopts or runs the {@value #CONTAINER_NAME} container. False = fall back. */
    private boolean startDocker() {
        try {
            String running = runDocker(Duration.ofSeconds(15),
                    "ps", "--filter", "name=" + CONTAINER_NAME, "--format", "{{.Names}}");
            if (running != null && running.lines().anyMatch(CONTAINER_NAME::equals)) {
                log.info("Adopting already-running MediaMTX container '{}'.", CONTAINER_NAME);
            } else {
                log.info("Starting MediaMTX container '{}' from {} (first run pulls the image — "
                        + "this can take a while)...", CONTAINER_NAME, DOCKER_IMAGE);
                String id = runDocker(DOCKER_RUN_TIMEOUT, dockerRunArgs());
                if (id == null) {
                    return false;
                }
                log.info("MediaMTX container started ({}).", id.trim());
            }
            dockerManaged = true;
            available = waitForApi(() -> true);
            if (!available) {
                log.warn("MediaMTX container did not answer its API on 127.0.0.1:{} within {}s "
                        + "(docker logs {} for details).", apiPort, STARTUP_WAIT.toSeconds(), CONTAINER_NAME);
                stop();
                return false;
            }
            log.info("MediaMTX up (docker) — API on 127.0.0.1:{}, WebRTC/WHEP on :{}, ICE on :{}.",
                    apiPort, webrtcPort, ICE_PORT);
            return true;
        } catch (IOException e) {
            log.warn("docker CLI not usable ({}).", e.toString());
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
                // Docker Desktop NAT: advertise the loopback the browser can
                // actually reach, over the single published ICE port.
                "-e", "MTX_WEBRTCADDITIONALHOSTS=127.0.0.1",
                "-e", "MTX_WEBRTCLOCALTCPADDRESS=:" + ICE_PORT,
                "-e", "MTX_WEBRTCLOCALUDPADDRESS=:" + ICE_PORT,
                // server listeners off — RTSP stays a client-side source protocol
                "-e", "MTX_RTSP=no",
                "-e", "MTX_RTMP=no",
                "-e", "MTX_HLS=no",
                "-e", "MTX_SRT=no",
                // MediaMTX's default auth only allows the `api` action from
                // 127.0.0.1/::1 — but inside the container our API calls
                // arrive from the Docker NAT gateway IP and would get 401.
                // Grant `api` to the anonymous user instead; actual exposure
                // is still limited by publishing the API port on host
                // loopback only.
                "-e", "MTX_AUTHINTERNALUSERS_0_USER=any",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_0_ACTION=publish",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_1_ACTION=read",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_2_ACTION=playback",
                "-e", "MTX_AUTHINTERNALUSERS_0_PERMISSIONS_3_ACTION=api"));
        args.add(DOCKER_IMAGE);
        return args.toArray(String[]::new);
    }

    /** Runs a docker command, returns stdout on exit 0, null (with a warn) otherwise. */
    private String runDocker(Duration timeout, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("docker");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        // Drain stderr concurrently: `docker run` streams pull progress there,
        // and a full pipe would deadlock the sequential stdout read below.
        var stderrFuture = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try {
                stderrFuture.complete(new String(
                        process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                stderrFuture.complete("");
            }
        });
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr;
        try {
            stderr = stderrFuture.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            stderr = "";
        }
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.warn("docker {} timed out after {}s.", args[0], timeout.toSeconds());
            return null;
        }
        if (process.exitValue() != 0) {
            log.warn("docker {} failed (exit {}): {}", args[0], process.exitValue(), stderr.trim());
            return null;
        }
        return stdout;
    }

    // ---- exe mode ----------------------------------------------------------

    private void startExe() {
        if (!Files.isRegularFile(exePath)) {
            log.info("MediaMTX exe not found at {} — running in HLS-only mode "
                    + "(set cameracheck.mediamtx.path to enable WebRTC).", exePath);
            return;
        }
        try {
            configFile = Files.createTempFile("camera-check-mediamtx", ".yml");
            Files.writeString(configFile, generatedConfig(), StandardCharsets.UTF_8);

            Path logFile = logsRoot.resolve("mediamtx.log");
            ProcessBuilder builder = new ProcessBuilder(
                    exePath.toString(), configFile.toString());
            builder.directory(logsRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());
            exeProcess = builder.start();
            log.info("Started MediaMTX exe ({}) — API on 127.0.0.1:{}, WebRTC/WHEP on :{}, log {}",
                    exePath, apiPort, webrtcPort, logFile);

            available = waitForApi(exeProcess::isAlive);
            if (!available) {
                log.warn("MediaMTX did not answer its API within {}s — killing it and "
                        + "degrading to HLS-only mode (see {}).",
                        STARTUP_WAIT.toSeconds(), logFile);
                stop();
            }
        } catch (IOException e) {
            log.warn("Could not start MediaMTX exe ({}) — running in HLS-only mode.", e.toString());
            available = false;
        }
    }

    /**
     * Minimal MediaMTX config for exe mode: control API + WebRTC only.
     * Everything not set here keeps the MediaMTX default.
     */
    private String generatedConfig() {
        return """
                logLevel: info
                logDestinations: [stdout]

                # Control API, loopback only — the backend is the only client.
                api: yes
                apiAddress: 127.0.0.1:%d

                # Media plane: WebRTC/WHEP for browsers.
                webrtc: yes
                webrtcAddress: :%d
                webrtcAllowOrigin: '*'

                # RTSP is used only as a *source* protocol (pulling from cameras);
                # the server listeners below would only collide with cameras/backend.
                rtsp: no
                rtmp: no
                hls: no
                srt: no

                paths: {}
                """.formatted(apiPort, webrtcPort);
    }

    // ---- shared ------------------------------------------------------------

    private boolean waitForApi(java.util.function.BooleanSupplier stillStarting) {
        long deadline = System.nanoTime() + STARTUP_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!stillStarting.getAsBoolean()) {
                return false;
            }
            try {
                HttpResponse<String> response = http.send(
                        apiRequest("GET", "/v3/config/global/get", null),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return true;
                }
            } catch (IOException e) {
                // not up yet — retry
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
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

    /** True when MediaMTX is up and its API answered. */
    public boolean available() {
        if (!available) {
            return false;
        }
        return exeProcess == null || exeProcess.isAlive();
    }

    /**
     * Registers {@code cam-{streamId}} as an on-demand RTSP source path and
     * returns the WHEP URL for it, or {@code null} when the relay is not
     * available or the registration failed (callers degrade to HLS).
     *
     * @param credentialedRtspUrl RTSP URL with credentials embedded — passed to
     *        MediaMTX only, never logged (masked on failure).
     */
    public String registerPath(String streamId, String credentialedRtspUrl) {
        if (!available()) {
            return null;
        }
        String json = "{\"source\":" + jsonString(credentialedRtspUrl)
                + ",\"sourceOnDemand\":true,\"rtspTransport\":\"tcp\"}";
        try {
            HttpResponse<String> response = http.send(
                    apiRequest("POST", "/v3/config/paths/add/" + pathName(streamId), json),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("MediaMTX rejected path {} (HTTP {}: {}) — stream {} degrades to HLS.",
                        pathName(streamId), response.statusCode(),
                        StreamManager.maskCredentialsInText(response.body()), streamId);
                return null;
            }
            log.info("Registered MediaMTX path {} for {}", pathName(streamId),
                    StreamManager.maskCredentials(credentialedRtspUrl));
            return whepUrl(streamId);
        } catch (IOException e) {
            log.warn("MediaMTX API unreachable ({}) — stream {} degrades to HLS.",
                    e.toString(), streamId);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Best-effort removal of the stream's MediaMTX path. */
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
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + apiPort + path))
                .timeout(API_TIMEOUT)
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
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
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    @PreDestroy
    public void stop() {
        available = false;
        if (dockerManaged) {
            // --rm removes the container once stopped.
            try {
                runDocker(Duration.ofSeconds(15), "stop", "-t", "2", CONTAINER_NAME);
            } catch (IOException e) {
                log.debug("Could not stop MediaMTX container: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dockerManaged = false;
        }
        if (exeProcess != null) {
            exeProcess.destroyForcibly();
            try {
                exeProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exeProcess = null;
        }
        if (configFile != null) {
            try {
                Files.deleteIfExists(configFile);
            } catch (IOException ignored) {
                // best effort
            }
            configFile = null;
        }
    }
}
