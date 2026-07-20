# Camera Checker — Frontend

Single-page workspace UI for checking RTSP / ONVIF / ISAPI cameras. Talks to the Spring Boot backend at `http://localhost:8080` (see `../API-CONTRACT.md`). Video playback prefers low-latency WebRTC (WHEP, when the backend runs the MediaMTX relay) and falls back to [hls.js](https://github.com/video-dev/hls.js).

## Stack

- Vite + vanilla JavaScript (no framework)
- Native WebRTC (standard WHEP client, no library) for low-latency playback
- hls.js for HLS playback / fallback
- Pure CSS (`src/style.css`), dark theme

## Run

```sh
npm install
npm run dev
```

Open http://localhost:5173. The backend must be running on port 8080 (with `ffmpeg` on PATH) for streaming to work. Backend base URL can be overridden with `VITE_BACKEND_BASE`.

## Build

```sh
npm run build
```

Output goes to `dist/`. With the repo's portable Node: `& '..\.tools\node-v22.14.0-win-x64\npm.cmd' run build`.

## Layout

Two-column workspace on desktop (stacks vertically below ~980 px, player first):

- **Left column — connection & device**
  - **Connection card**: segmented source selector (*RTSP URL* vs *Camera (ONVIF / ISAPI)*).
    - RTSP mode: URL + optional credentials + Play.
    - Camera mode: Manufacturer/Driver dropdown (lazily loaded from `GET /api/camera/drivers` on first open; falls back to Generic ONVIF with a warning on older backends), host, port, username, password, **Get Profiles**.
  - **Device card**: manufacturer / model / firmware / serial after a successful profiles call.
  - **Profiles card**: compact rows with MAIN/SUB/OTHER badge, codec · resolution · fps · bitrate summary, an expandable "Details" section (full video/audio encoder config, token, RTSP URI), and a per-profile Play button. The currently playing profile is highlighted green.
  - **Enable ONVIF card** (provisioning): shown only when the selected driver reports `canProvisionOnvif: true` (e.g. Hikvision/ISAPI). Enter a new ONVIF username + password and press *Enable ONVIF on device* → `POST /api/camera/onvif/provision` using the device credentials from the connection card. On success a green panel shows `integrationStatus`, `userStatus` and the `note`; errors surface in the global banner.
- **Right column — player & details**
  - Player with status chip (Idle / Starting / Live · WebRTC / Live · HLS / Stopped) and Stop button.
  - **PTZ overlay** (camera-mode streams only): a 4-way hold-to-move pad (◀ ▶ ▲ ▼) bottom-center of the video, visible on hover/touch. Pointer down = continuous move (`POST /api/camera/ptz/continuous`, speed ±40), release/leave = stop; arrow keys work while the player has focus (keyup = stop). Requests are serialized (latest-wins) so the final stop is always delivered — also on Stop and page unload. If the driver/camera has no PTZ, a one-time note is shown and the pad is hidden for that stream. The channel is derived from Hikvision stream ids (101 → channel 1, 201 → 2), default 1.
  - Stream details panel: profile + encoder summary, stream ID, transport (WEBRTC/HLS, incl. the fallback reason), RTSP/WHEP/HLS URLs (monospace), start time, and **live health** polled from `GET /api/stream/{id}` every 5 s — when ffmpeg dies the backend-reported `error` is shown.
- Global error banner at the top (API error envelope, backend-down detection, driver-aware `ONVIF_NOT_ENABLED` wording).

## Flows

- **Playback transports**: when `POST /api/stream/start` returns a `whepUrl`, the UI tries WebRTC first — a standard WHEP handshake (recvonly offer, POST `application/sdp`, answer) straight against the MediaMTX relay, no client library. If the handshake fails or no video frame arrives within ~4 s, the peer connection is closed and playback falls back to the HLS path below; the reason appears in the details panel. Stopping a WebRTC stream closes the `RTCPeerConnection`, best-effort DELETEs the WHEP session, and still DELETEs the backend stream.
- **HLS playback** (primary without MediaMTX, fallback otherwise): the backend transcodes to HLS; the UI polls the playlist for up to ~15 s (in parallel with stream health, so ffmpeg failures surface immediately with their cause) before playback starts. hls.js fatal errors are retried a bounded number of times, then the stream is torn down. Health polling runs in both transports (ffmpeg keeps running server-side).
- **Camera discovery**: pick a driver → Get Profiles (`POST /api/camera/profiles`) → device info + profiles → Play any profile.
- **ONVIF provisioning**: if a profiles call fails with `ONVIF_NOT_ENABLED` while a provisioning-capable driver is selected, the Enable ONVIF card is auto-highlighted (amber) with an explainer — the driver can switch the ONVIF integration protocol on and create an ONVIF user via the vendor's management API. Some firmware needs a reboot afterwards (the backend `note` says so).
- Only one stream plays at a time; starting a new one stops the previous one. The active backend stream is also stopped (best effort, `keepalive`) on page unload. Idle streams are auto-reaped by the backend after 5 min.

## Code layout

```
src/
  api.js          single API client (drivers, profiles, provision, PTZ, stream start/stop/health, playlist wait)
  state.js        shared mutable app state
  main.js         bootstrap, mode switching, driver loading, form wiring
  ui/dom.js       workspace DOM skeleton, element refs, format helpers
  ui/banner.js    global error banner
  ui/player.js    WHEP (WebRTC) client + hls.js fallback, playlist wait, health polling, details panel
  ui/profiles.js  device card + profile rows
  ui/provision.js Enable-ONVIF card + auto-suggestion
  ui/ptz.js       hold-to-move PTZ overlay (serialized continuous-move requests)
```
