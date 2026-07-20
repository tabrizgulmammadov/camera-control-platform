# Camera Control Platform Backend

Spring Boot API for camera discovery, WebRTC playback, and PTZ control.

## Prerequisites

- JDK 25 (JDK 17+ also works when the Maven compiler version is adjusted).
- Docker Desktop, running with the Linux container engine available.
- No Maven installation is required; the Maven Wrapper is included.

The backend starts the official `bluenviron/mediamtx:latest` image as the
`cameracheck-mediamtx` container. MediaMTX is Docker-only: there is no bundled
executable, ffmpeg process, or HLS fallback. If Docker is unavailable,
`POST /api/stream/start` returns `STREAM_ERROR` until Docker Desktop is running.

Verify Docker before starting the backend:

```powershell
docker info
```

## Run

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The API is available at `http://localhost:8080`.

## Streaming

`POST /api/stream/start` registers the camera RTSP source with MediaMTX and
returns a WHEP URL with `transport: "WEBRTC"`. The frontend plays that URL via
WebRTC. The MediaMTX API is bound to loopback port 9997; WHEP uses port 8889;
ICE uses port 8189 over TCP and UDP. MediaMTX's RTSP, RTMP, HLS, and SRT server
listeners are disabled because cameras are RTSP sources, not MediaMTX clients.
