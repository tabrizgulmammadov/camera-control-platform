# Camera Control Platform — Frontend

Single-page workspace UI for ONVIF / manufacturer-driver (Hikvision ISAPI, ...) cameras. There is no manual "enter an RTSP URL" mode — every stream comes from a driver-discovered profile. Talks to the Spring Boot backend at `http://localhost:8080` (see `../API-CONTRACT.md`). Video playback is low-latency WebRTC (WHEP) against the backend's MediaMTX relay — there is no HLS fallback in this build.

## Stack

- Vite + vanilla JavaScript (no framework)
- Native WebRTC (standard WHEP client, no library) for playback
- Pure CSS (`src/style.css`), dark theme

## Run

```sh
npm install
npm run dev
```

Open http://localhost:5173. The backend (with its MediaMTX relay) must be running on port 8080 for streaming to work. Backend base URL can be overridden with `VITE_BACKEND_BASE`.

## Build

```sh
npm run build
```

Output goes to `dist/`.

## Layout

Two-column workspace on desktop (stacks vertically on narrow widths, player first):

- **Left column — connection & device**
  - **Camera Connection card**: Manufacturer/Driver dropdown (lazily loaded from `GET /api/camera/drivers` on first load; falls back to Generic ONVIF with a warning on older backends), host, port, username, password, **Get Profiles**.
  - **Device card**: manufacturer / model / firmware / serial after a successful profiles call, plus an **ONVIF status indicator** for provisioning-capable drivers (green "ONVIF: enabled" chip, or a red "ONVIF: disabled" chip with an *Enable ONVIF…* button).
  - **Profiles card**: compact rows with MAIN/SUB/OTHER badge, codec · resolution · fps · bitrate summary, an expandable "Details" section (full video/audio encoder config, token, RTSP URI), and a per-profile Play button. The currently playing profile is highlighted green.
  - **ONVIF Setup modal**: opened from the Device card's *Enable ONVIF…* button, or automatically when a profiles call fails with `ONVIF_NOT_ENABLED` while a provisioning-capable driver is selected. Shows the current status, an explainer, fields for a new ONVIF username/password, and *Enable ONVIF on device* → `POST /api/camera/onvif/provision`. On success a green panel shows `integrationStatus`, `userStatus` and the `note`, and the status indicator flips to enabled.
- **Right column — player & details**
  - Player with status chip (Idle / Starting / Live - WebRTC / Stopped) and Stop button.
  - **PTZ overlay** (shown for any playing camera profile): a 4-way hold-to-move pad (◀ ▶ ▲ ▼) bottom-center of the video, visible on hover/touch. Pointer down = continuous move (`POST /api/camera/ptz/continuous`, speed ±40), release/leave = stop; arrow keys work while the player has focus (keyup = stop). If the driver/camera has no PTZ, a one-time note is shown and the pad is hidden for that stream. The channel is derived from Hikvision stream ids (101 → channel 1, 201 → 2), default 1.
  - Stream details panel: profile + encoder summary, stream ID, transport, RTSP/WHEP URLs (monospace), start time, and **live health** polled from `GET /api/stream/{id}` every 5 s.
- Global error banner at the top (API error envelope, backend-down detection, driver-aware `ONVIF_NOT_ENABLED` wording).

## Flows

- **Camera discovery**: pick a driver → Get Profiles (`POST /api/camera/profiles`) → device info + profiles → Play any profile. The ONVIF status indicator is checked automatically right after a successful fetch (provisioning-capable drivers only).
- **Playback**: `POST /api/stream/start` returns a `whepUrl`; the UI performs a standard WHEP handshake (recvonly offer, POST `application/sdp`, answer) straight against the MediaMTX relay, no client library. Stopping a stream closes the `RTCPeerConnection`, best-effort DELETEs the WHEP session, and DELETEs the backend stream.
- **ONVIF provisioning**: if a profiles call fails with `ONVIF_NOT_ENABLED` while a provisioning-capable driver is selected, the ONVIF Setup modal opens directly with an explainer — the driver can switch the ONVIF integration protocol on and create an ONVIF user via the vendor's management API.
- **PTZ**: hold an arrow (mouse/touch/keyboard) on the live player to move; release to stop. Hidden automatically for cameras/drivers without PTZ.
- Only one stream plays at a time; starting a new one stops the previous one. The active backend stream is also stopped, and any in-flight PTZ move is stopped, both best effort (`keepalive`) on page unload.

## Code layout

```
src/
  api.js          single API client (drivers, profiles, ONVIF status/provision, PTZ, stream start/stop/health)
  state.js        shared mutable app state
  main.js         bootstrap, driver loading, connection form wiring
  ui/dom.js       workspace DOM skeleton, element refs, format helpers
  ui/banner.js    global error banner
  ui/player.js    WHEP (WebRTC) client, health polling, details panel
  ui/profiles.js  device card + profile rows
  ui/provision.js ONVIF status indicator + Enable-ONVIF setup modal + auto-suggestion
  ui/ptz.js       hold-to-move PTZ overlay (serialized continuous-move requests)
```
