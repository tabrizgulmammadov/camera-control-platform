# Camera Check — Backend

Spring Boot REST API implementing the shared contract in `../API-CONTRACT.md`:
camera device/profile inspection (generic ONVIF plus Hikvision ISAPI drivers),
RTSP playback (low-latency WebRTC via a managed MediaMTX relay, with an ffmpeg
RTSP-to-HLS fallback) and PTZ control.

## Prerequisites

- **JDK 25** (any JDK >= 17 works if you lower `<java.version>` in `pom.xml`)
- **ffmpeg on PATH** (only needed for the `/api/stream/*` endpoints)
- **Docker Desktop** (optional) — enables the low-latency WebRTC/WHEP
  transport: the backend runs the official `bluenviron/mediamtx` image as a
  managed container (`cameracheck-mediamtx`). Launch mode is controlled by
  `cameracheck.mediamtx.mode`: `docker` (default), `exe` (bare exe at
  `..\.tools\mediamtx\mediamtx.exe`, override with
  `cameracheck.mediamtx.path`; automatic fallback when docker is unusable), or
  `off`. Without either, the backend logs an info line and serves HLS only.
  `cameracheck.mediamtx.api-port` / `cameracheck.mediamtx.webrtc-port`
  override the default ports 9997/8889.
- No Maven install needed — the Maven wrapper is included.

## Run

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The API listens on `http://localhost:8080`. CORS is open for `/api/**` and `/hls/**`.

Build a jar instead:

```powershell
.\mvnw.cmd -q package
java -jar target\camera-check-backend-0.1.0.jar
```

## Endpoints

| Method | Path                    | Purpose                                        |
| ------ | ----------------------- | ---------------------------------------------- |
| GET    | `/api/camera/drivers`   | List available drivers (never instantiates them) |
| POST   | `/api/camera/profiles`  | Device info + profiles for any driver (`driver` field, default `ONVIF`) |
| POST   | `/api/onvif/profiles`   | Legacy alias for `/api/camera/profiles` with `driver=ONVIF` |
| POST   | `/api/camera/ptz/continuous` | Continuous PTZ move (speeds −100…100, 0/0 = stop) (204) |
| POST   | `/api/stream/start`     | Start playback: registers a MediaMTX WHEP path (when available) + spawns ffmpeg RTSP -> HLS. Response carries `whepUrl`/`transport`. |
| GET    | `/api/stream/{id}`      | Stream health/details                          |
| DELETE | `/api/stream/{id}`      | Kill ffmpeg + delete segments (204)            |
| GET    | `/hls/{id}/index.m3u8`  | HLS playlist (static; retry ~15 s after start) |

Errors always use the envelope `{ "code": "...", "message": "..." }` with codes
`ONVIF_NOT_ENABLED`, `ONVIF_AUTH_FAILED`, `DEVICE_UNREACHABLE`, `STREAM_ERROR`,
`BAD_REQUEST`, `INTERNAL`.

## Architecture

Genetec-style driver abstraction: the API layer only sees the protocol-agnostic
`CameraDriver` / `CameraConnection` interfaces (`com.cameracheck.domain`).
Drivers are **lazy-loaded**: each driver package contributes a lightweight
`CameraDriverDescriptor` bean (id + displayName + factory) that the
`CameraDriverRegistry` collects; the driver bean itself is `@Lazy` and only
gets instantiated the first time a request selects it (an info line is logged
on first instantiation). `GET /api/camera/drivers` therefore never creates a
driver.

Bundled drivers:

- **ONVIF** (`com.cameracheck.driver.onvif`) — raw SOAP 1.2 over the JDK
  `HttpClient` with WS-Security UsernameToken password digest, parsed with
  namespace-aware DOM/XPath.
- **HIKVISION** (`com.cameracheck.driver.hikvision`) — Hikvision ISAPI
  (REST/XML) with hand-rolled HTTP Digest auth (RFC 7616/2617 MD5, `qop=auth`,
  Basic fallback). Maps `/ISAPI/System/deviceInfo` and
  `/ISAPI/Streaming/channels` to the domain models: channel `X01` → MAIN,
  `X02` → SUB, fps normalized from Hikvision's ×100 encoding (2500 → 25),
  RTSP URIs as `rtsp://{host}:554/Streaming/Channels/{id}`.

Vendor drivers are added by contributing another lazy `CameraDriver` bean plus
its `CameraDriverDescriptor`.

Driver capabilities are optional interfaces on a `CameraConnection`,
discovered via `instanceof` at the API boundary: `OnvifProvisioning`
(Hikvision) and `PtzControl` (Hikvision via
`/ISAPI/PTZCtrl/channels/{ch}/continuous`; ONVIF via the PTZ service
`ContinuousMove`/`Stop` using the channel's media profile token).

Streaming (`com.cameracheck.streaming`): `StreamManager` spawns one ffmpeg per
stream (`-rtsp_transport tcp`, `-c copy` remux, 1-second rolling HLS playlist)
into `%TEMP%\camera-check-hls\{streamId}`, served statically at `/hls/**`.
Audio is dropped (`-an`) because typical camera audio (G.711) is not
HLS-compatible without transcoding. Streams idle for more than 5 minutes are
reaped automatically; polling `GET /api/stream/{id}` counts as activity.

Low latency (`MediaMtxRelay`): at startup the backend brings up MediaMTX (API
on `127.0.0.1:9997`, WebRTC/WHEP on `:8889`, its RTSP/RTMP/HLS/SRT servers
disabled). Default is docker mode: `docker run -d --rm --name
cameracheck-mediamtx` of the official image, configured via `MTX_*` env vars,
publishing only 9997 (loopback), 8889 and the single ICE port 8189 (tcp+udp);
`MTX_WEBRTCADDITIONALHOSTS=127.0.0.1` makes ICE candidates that are reachable
through Docker Desktop's NAT (without it the WHEP handshake succeeds but no
media flows). An already-running container with that name is adopted; on
shutdown the container is stopped (`--rm` removes it). Exe mode instead starts
the bare exe with a generated config, its log next to the ffmpeg logs (outside
the HTTP-served tree). Each stream start additionally registers
`cam-{streamId}` as an on-demand TCP RTSP source via the MediaMTX REST API and
returns `whepUrl: http://localhost:8889/cam-{streamId}/whep` with
`transport: "WEBRTC"`. Any MediaMTX failure degrades that stream to HLS —
relay errors never fail a stream start.
