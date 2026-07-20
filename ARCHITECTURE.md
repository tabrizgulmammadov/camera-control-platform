# Camera Checker — Architecture

A lab tool for verifying IP cameras: query ONVIF profiles/encoder configs, play
RTSP streams in the browser (low-latency WebRTC via a managed MediaMTX relay,
falling back to an ffmpeg RTSP→HLS pipeline), and drive PTZ. The
frontend⇄backend wire format is defined in `API-CONTRACT.md` (source of truth —
change both sides together).

## Layers (backend, `com.cameracheck`)

```
api  ──►  domain  ◄──  driver.onvif
 │              ◄──  driver.hikvision
 └──►  streaming
```

- **`domain`** — protocol-agnostic core. `CameraDriver` (one per protocol) opens a
  `CameraConnection`, which yields neutral models: `DeviceInformation`,
  `MediaProfile`, `VideoEncoderConfig`, `AudioEncoderConfig`, `StreamType`.
  Failures are `CameraException` carrying a contract `ErrorCode`. No Spring MVC,
  no protocol types. `CameraDriverRegistry` collects lightweight
  `CameraDriverDescriptor` beans (`id`, `displayName`, lazy factory); listing
  drivers (`GET /api/camera/drivers`) touches only descriptors, and the actual
  `@Lazy` driver bean is instantiated on the first `forProtocol(id)` call —
  observable via the "driver instantiated (lazy first use)" info log.
- **`driver.onvif`** — the only place that knows SOAP. Raw SOAP 1.2 over the JDK
  `HttpClient` (`SoapClient`), WS-Security UsernameToken PasswordDigest
  (`WsSecurityHeader`), namespace-tolerant DOM/XPath extraction (`OnvifParsers`),
  and session logic (`OnvifConnection`): GetDeviceInformation, media XAddr
  discovery via GetCapabilities → GetServices fallback (XAddr host/port pinned to
  the user-supplied endpoint, since NATed cameras advertise internal IPs),
  GetProfiles, per-profile GetStreamUri (RTP-Unicast/RTSP).
- **`streaming`** — protocol-independent RTSP→HLS relay. `StreamManager` spawns
  one ffmpeg per stream (`-c copy`, rolling 5-segment playlist) into a per-stream
  temp dir, tracks sessions in a `ConcurrentHashMap`, reaps streams idle >5 min,
  and kills all ffmpeg processes on shutdown (`@PreDestroy`). ffmpeg output is
  redirected to a log file so its pipes can never block.
- **Control plane vs media plane** — the backend is the *control plane*: it
  talks to cameras, validates, orchestrates. The *media plane* for low latency
  is a managed MediaMTX instance (`streaming.MediaMtxRelay`), exposing only the
  control API on `127.0.0.1:9997` and WebRTC/WHEP on `:8889`
  (`webrtcAllowOrigin: '*'`); MediaMTX's own RTSP/RTMP/HLS/SRT server
  listeners are disabled so nothing collides — RTSP remains purely a
  client-side *source* protocol. Launch mode (`cameracheck.mediamtx.mode`):
  `docker` (default) runs the official `bluenviron/mediamtx` image as
  container `cameracheck-mediamtx` (`docker run -d --rm`, config via `MTX_*`
  env vars, ports 9997/8889 plus the single ICE port 8189 tcp+udp published;
  `MTX_WEBRTCADDITIONALHOSTS=127.0.0.1` because behind Docker Desktop NAT the
  container's own host candidates are unreachable — the handshake would
  succeed but no media would flow); an already-running container is adopted,
  and docker-CLI failure falls back to `exe` mode (bare
  `..\.tools\mediamtx\mediamtx.exe` child process with a generated config, log
  under `HlsStorage.logsRoot()` — outside the HTTP-served tree since MediaMTX
  echoes credentialed source URLs); `off` = HLS-only. `@PreDestroy` stops the
  container/process. Per stream start, the relay registers
  `cam-{streamId}` as an on-demand TCP RTSP source via the MediaMTX REST API
  and the start response gains `whepUrl` + `transport:"WEBRTC"`; browsers do a
  standard WHEP handshake straight against MediaMTX. Missing exe or any relay
  error → HLS-only (`transport:"HLS"`, `whepUrl:null`) — the ffmpeg pipeline
  always runs and stays the source of details/health. MediaMTX logs go to
  `HlsStorage.logsRoot()` (outside the HTTP-served tree: its log echoes
  credentialed source URLs).
- **Driver capabilities** — optional interfaces a `CameraConnection` may
  implement, discovered via `instanceof` at the API boundary:
  - `OnvifProvisioning` — enable the ONVIF protocol + create the ONVIF user via
    the vendor's management API (`POST /api/camera/onvif/provision`). Each
    descriptor carries a static `canProvisionOnvif` flag so the frontend can
    offer the flow without instantiating the driver.
  - `PtzControl` — `continuousMove(channel, pan, tilt)` with speeds −100…100
    (0/0 = stop), exposed as `POST /api/camera/ptz/continuous`. Hikvision
    implements it via `PUT /ISAPI/PTZCtrl/channels/{ch}/continuous`; the ONVIF
    driver via the PTZ service (`ContinuousMove`/`Stop`, velocities normalized
    to −1…1, channel = Nth media profile token). Non-PTZ drivers → 400; a
    fixed camera's rejection passes through with the device's message.
- **`driver.hikvision`** — Hikvision native driver over ISAPI (REST/XML).
  `IsapiClient` GETs `/ISAPI/System/deviceInfo` and `/ISAPI/Streaming/channels`
  with hand-rolled HTTP Digest auth (`HttpDigestAuth`, RFC 7616 MD5 + qop=auth;
  Basic fallback when that is all the camera offers). `IsapiParsers` maps
  channels to `MediaProfile` (channel id X01→MAIN / X02→SUB, fps reported ×100
  normalized, CBR/VBR bitrate, RTSP URI `rtsp://host:554/Streaming/Channels/{id}`).
- **`api`** — HTTP boundary. Controllers stay thin; `CameraProfileService` owns
  validation and the driver-agnostic inspect flow (resolve driver → connect →
  device info + profiles → `CameraProfilesResponse`). `CameraController` serves
  the generic endpoints, `OnvifController` is the legacy alias.
  `ApiExceptionHandler` converts every failure to the `{ code, message }`
  envelope. `WebConfig` sets CORS and serves the HLS temp tree at `/hls/**`.

Dependency rule: `api` and `streaming` never import `driver.*` types;
`driver.*` never imports `api.*`. Everything meets in `domain`.

## Adding a vendor driver (e.g. Axis VAPIX)

1. Create `com.cameracheck.driver.axis` with a driver implementing
   `CameraDriver` and a `CameraConnection` mapping VAPIX responses to the
   `domain` models.
2. Add an `AxisDriverConfig` `@Configuration` in that package: a `@Bean @Lazy`
   driver bean plus a `CameraDriverDescriptor("AXIS", "Axis (VAPIX)",
   provider::getObject)` bean.
3. Done. The driver appears in `GET /api/camera/drivers` and is selectable via
   `POST /api/camera/profiles {"driver":"AXIS", ...}` — zero changes in `api`,
   `domain`, or `streaming`, and the driver class is not even loaded until the
   first request uses it.

## Security notes (lab-tool trade-offs)

- **CORS is `*`** by design (see `WebConfig` javadoc and API-CONTRACT.md): the
  backend binds to localhost, holds no cookies/sessions, and
  `allowCredentials` is off. Tighten before any non-lab use.
- **Credentials never hit logs or HTTP**: RTSP URLs are masked
  (`StreamManager.maskCredentials`) before logging; ffmpeg logs — which can echo
  the credentialed input URL — live *outside* the HTTP-served HLS tree
  (`HlsStorage.logsRoot()`). Passwords are only used for the WSSE digest and the
  ffmpeg command line.
- **HLS serving**: only server-generated stream IDs create directories; Spring's
  resource handler resolves paths against the HLS root and rejects traversal.

## Frontend (`frontend/`)

Vite + vanilla JS + hls.js single page. The camera tab has a manufacturer/driver
dropdown populated lazily from `GET /api/camera/drivers` (hardcoded ONVIF
fallback if the endpoint is missing). `src/api.js` is the contract client
(error envelope → `ApiError`, playlist readiness polling with cancellation);
`src/main.js` owns state and rendering. One active stream at a time: `play()`
stops/destroys the previous hls.js instance first, guards overlapping actions
with a busy flag, and ignores stale playlist results if the user stopped or
restarted meanwhile. Fatal hls.js errors get a bounded retry budget before the
stream is torn down. On page unload the active backend stream is deleted
best-effort with a keepalive request.
