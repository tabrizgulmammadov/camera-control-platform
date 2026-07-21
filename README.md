# Camera Control Platform

A lab tool for discovering IP cameras (ONVIF and vendor-specific drivers), viewing their live
video at low latency over WebRTC, and driving PTZ — all through one browser workspace.

There is no manual "type an RTSP URL" mode: every stream is discovered through a camera driver
(ONVIF, or a manufacturer-specific one such as Hikvision ISAPI), and every RTSP URL played by the
app is a profile's own advertised `rtspUri`.

## How it fits together

```
┌──────────────┐   HTTP (JSON)   ┌──────────────────┐   SOAP / ISAPI (HTTP)   ┌──────────┐
│   Frontend   │ ───────────────▶│  Backend (Spring │ ───────────────────────▶│  Camera  │
│ (Vite + JS)  │◀─────────────── │   Boot, :8080)   │◀──────────────────────── │ (device) │
└──────┬───────┘   whepUrl,      └─────────┬────────┘   profiles, PTZ, ONVIF   └──────────┘
       │           profiles, PTZ           │            provisioning
       │                                   │ REST (paths API, :9997)
       │ WebRTC (WHEP, :8889)              ▼
       └─────────────────────────▶ ┌──────────────────┐   RTSP (pulled          ┌──────────┐
                                    │  MediaMTX         │  on demand)            │  Camera  │
                                    │ (Docker container)│───────────────────────▶│ (stream) │
                                    └──────────────────┘                        └──────────┘
```

- **Frontend** (`frontend/`) — a single-page workspace. Pick a manufacturer/driver, enter the
  device's host/port/credentials, fetch its profiles (streams + encoder configs), press Play, and
  optionally drive PTZ on the live view. See [frontend/README.md](frontend/README.md) for the UI
  layout and flows in detail.
- **Backend** (`backend/`) — a Spring Boot API that is the **control plane**: it never touches
  video bytes. It talks to the camera over each vendor's own management protocol (ONVIF SOAP,
  Hikvision ISAPI, ...) to fetch device info/profiles, provision ONVIF, and send PTZ commands; and
  it talks to MediaMTX's small REST API to register/remove RTSP sources. See
  [backend/README.md](backend/README.md) for prerequisites and run instructions.
- **MediaMTX** (`docker-compose.yml`) — the **media plane**: a single Docker container that pulls
  the camera's RTSP stream on demand and serves it to the browser as WebRTC (via the standard WHEP
  protocol). The backend manages *which* streams it carries; MediaMTX only ever moves bytes.

This control-plane/media-plane split is deliberate: the Java backend stays a small, protocol-aware
control service, while the actual audio/video transcoding and delivery is done by a
purpose-built, battle-tested media server running in its own container.

## End-to-end workflow

1. **Discover drivers** — the frontend calls `GET /api/camera/drivers` on load. The backend lists
   its registered drivers (currently `ONVIF` and `HIKVISION`) without instantiating any of them —
   drivers are lazily constructed only the first time they're actually used.
2. **Get profiles** — pick a driver, enter the device's host/port/credentials, click *Get
   Profiles*. The backend connects using that driver's protocol (ONVIF SOAP with WS-Security, or
   Hikvision ISAPI with HTTP Digest auth) and returns device info plus every stream profile
   (MAIN/SUB/OTHER, codec, resolution, frame rate, bitrate, and each profile's RTSP URI).
3. **(Optional) Enable ONVIF** — if a profiles call fails because ONVIF is disabled on the device,
   and the selected driver supports it (Hikvision does), the UI offers to enable the ONVIF
   integration protocol and create an ONVIF user directly on the camera, via that driver's own
   management API.
4. **Play** — clicking Play on a profile sends its `rtspUri` to `POST /api/stream/start`. The
   backend registers that RTSP URL as a path with MediaMTX and returns a WHEP URL. The frontend
   performs a standard WHEP handshake (an SDP offer/answer exchange) directly against MediaMTX and
   attaches the resulting WebRTC video stream to the `<video>` element — MediaMTX pulls the RTSP
   feed from the camera only once a viewer actually connects.
5. **Control PTZ** — while a profile is playing, a 4-way pad (or arrow keys) sends continuous
   pan/tilt speeds to `POST /api/camera/ptz/continuous`; the driver translates that into the
   vendor's own PTZ command (Hikvision ISAPI or ONVIF PTZ). Releasing the control sends a stop.
6. **Stop** — `DELETE /api/stream/{streamId}` removes the path from MediaMTX; idle streams are
   also auto-reaped after 5 minutes.

Every response — success or failure — follows one JSON contract described in full in
[API-CONTRACT.md](API-CONTRACT.md), including the shared error envelope
(`{ "code": "...", "message": "..." }`) used across every endpoint.

## Adding a new camera manufacturer

The backend is built so a new vendor driver never touches shared code:

1. Add a new package `com.cameracontrolplatform.driver.<vendor>` implementing `CameraDriver` /
   `CameraConnection` (and, optionally, the `OnvifProvisioning` / `PtzControl` capability
   interfaces if the vendor's API supports them).
2. Register a lazy `@Bean` for the driver plus a `CameraDriverDescriptor` (id + display name) —
   see `driver/hikvision/HikvisionDriverConfig.java` for the pattern to copy.
3. Done — the new driver shows up in `GET /api/camera/drivers` and works through every existing
   endpoint (profiles, streaming, PTZ, ONVIF provisioning where applicable) with no frontend
   changes required.

## Running it locally

```powershell
# 1. Start the media plane
docker compose up -d mediamtx

# 2. Start the backend (control plane) — see backend/README.md for prerequisites
cd backend
.\mvnw.cmd spring-boot:run

# 3. Start the frontend
cd frontend
npm install
npm run dev
```

Open http://localhost:5173, pick a driver, enter your camera's details, and go.

## Repository layout

```
API-CONTRACT.md      the wire format — source of truth for both frontend and backend
docker-compose.yml    the MediaMTX (media plane) container definition
backend/              Spring Boot control plane — see backend/README.md
frontend/             Vite + vanilla JS workspace UI — see frontend/README.md
```
