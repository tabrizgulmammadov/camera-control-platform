# Camera Checker — Shared API Contract (Frontend ⇄ Backend)

Backend base URL: `http://localhost:8080`
All request/response bodies are JSON. CORS must allow `http://localhost:5173` (frontend dev server) and `*` for simplicity.

## Error envelope (all endpoints)
On failure, HTTP 4xx/5xx with:
```json
{ "code": "ONVIF_NOT_ENABLED | ONVIF_AUTH_FAILED | DEVICE_UNREACHABLE | STREAM_ERROR | BAD_REQUEST | NOT_FOUND | INTERNAL", "message": "human readable detail" }
```
`ONVIF_NOT_ENABLED` is returned when the device does not answer ONVIF SOAP on the given port (connection refused / non-SOAP response / 404 on device service).
`NOT_FOUND` (HTTP 404) is returned for an unknown/expired `streamId` and for missing HLS files.

## 0) Driver discovery
`GET /api/camera/drivers` → 200
```json
{ "drivers": [ { "id": "ONVIF", "displayName": "Generic ONVIF", "canProvisionOnvif": false }, { "id": "HIKVISION", "displayName": "Hikvision (ISAPI)", "canProvisionOnvif": true } ] }
```
Listing drivers must NOT instantiate them — drivers are lazy-loaded on first use.

## 1) Get profiles + encoder configs (any driver)
`POST /api/camera/profiles`
```json
{ "driver": "ONVIF", "host": "192.168.1.64", "port": 80, "username": "admin", "password": "pass" }
```
`driver` is one of the ids from `/api/camera/drivers` (default `"ONVIF"` if omitted). Unknown driver id → 400 `BAD_REQUEST`.
`POST /api/onvif/profiles` (same body without `driver`) remains as a legacy alias for the ONVIF driver.
For `HIKVISION`, `port` is the HTTP port for ISAPI (default 80); errors map the same way (`ONVIF_NOT_ENABLED` is generalized to mean "the selected protocol is not answering on this port").
Response 200:
```json
{
  "deviceInfo": { "manufacturer": "...", "model": "...", "firmwareVersion": "...", "serialNumber": "..." },
  "profiles": [
    {
      "token": "Profile_1",
      "name": "mainStream",
      "streamType": "MAIN | SUB | OTHER",
      "videoEncoder": {
        "encoding": "H264 | H265 | JPEG",
        "resolution": { "width": 1920, "height": 1080 },
        "frameRate": 25,
        "bitrateKbps": 4096,
        "quality": 4.0,
        "govLength": 50,
        "profile": "Main"
      },
      "audioEncoder": { "encoding": "AAC", "bitrateKbps": 64, "sampleRateKhz": 8 },
      "rtspUri": "rtsp://192.168.1.64:554/Streaming/Channels/101"
    }
  ]
}
```
`streamType` heuristic: first profile / name contains "main" → MAIN; name contains "sub" or second profile → SUB.

## 1a) ONVIF status on the device (driver capability)
`POST /api/camera/onvif/status` — same body as 1b minus the onvif user fields.
Response 200: `{ "supported": true, "onvifEnabled": false }`
(`supported:false, onvifEnabled:null` when the driver has no management API for this.)
The frontend calls this automatically after a successful Get Profiles with a
provisioning-capable driver and shows an ONVIF status indicator; when disabled it
offers the enable screen (1b).

## 1b) Provision ONVIF on the device (driver capability)
`POST /api/camera/onvif/provision`
```json
{ "driver": "HIKVISION", "host": "10.206.13.8", "port": 80, "username": "admin", "password": "pass",
  "onvifUsername": "onvifuser", "onvifPassword": "OnvifPass1" }
```
Uses the vendor driver's management API (Hikvision: ISAPI) to enable the ONVIF integration
protocol on the camera and create (or update) the given ONVIF user. Response 200:
```json
{ "integrationStatus": "ONVIF integration protocol enabled", "userStatus": "ONVIF user 'onvifuser' created", "note": "If profiles still fail, reboot the camera — some firmware applies the setting only after restart." }
```
Drivers that don't support provisioning (e.g. the generic ONVIF driver itself) → 400
`BAD_REQUEST` with message "driver ... does not support ONVIF provisioning".
Errors map like the profiles endpoint (`ONVIF_AUTH_FAILED`, `DEVICE_UNREACHABLE`, ...).

## 2) Start playback (works for both modes)
`POST /api/stream/start`
```json
{ "rtspUrl": "rtsp://...", "username": "admin", "password": "pass" }
```
Credentials optional (may already be embedded in URL). Backend launches ffmpeg to transcode/remux RTSP → HLS.
Response 200:
```json
{ "streamId": "a1b2c3", "hlsUrl": "http://localhost:8080/hls/a1b2c3/index.m3u8",
  "whepUrl": "http://localhost:8889/cam-a1b2c3/whep", "transport": "WEBRTC",
  "details": { "rtspUrl": "rtsp://...", "startedAt": "ISO-8601" } }
```
`whepUrl`/`transport:"WEBRTC"` are present when the MediaMTX relay is available (exe at
`.tools\mediamtx\`, managed by the backend): the frontend then plays low-latency WebRTC
via standard WHEP and only falls back to `hlsUrl` if the WHEP handshake fails.
Without MediaMTX, `whepUrl` is null and `transport` is `"HLS"` (ffmpeg pipeline, unchanged).
HLS segments are served statically by the backend at `/hls/{streamId}/...`.
The playlist may take a few seconds to appear; frontend should retry fetching the m3u8 for ~15 s.

## 3) Stop playback
`DELETE /api/stream/{streamId}` → 204. Kills the ffmpeg process and deletes segment files. Streams idle >5 min are auto-reaped.

## 4) Stream details / health
`GET /api/stream/{streamId}` → 200 `{ "streamId": "...", "running": true, "rtspUrl": "...", "startedAt": "...", "ffmpegAlive": true }`

If the ffmpeg process has died (e.g. RTSP connect failure shortly after start), the response additionally
carries the cause: `{ ..., "running": false, "ffmpegAlive": false, "error": "ffmpeg exited with code 1: <last log lines, credentials masked>" }`.
The `error` field is omitted while ffmpeg is alive. Unknown `streamId` → 404 `{ "code": "NOT_FOUND", "message": "Unknown streamId: ..." }`.
Frontends should poll this endpoint while waiting for the playlist and surface `error` if `running` turns false.

## 5) PTZ control (driver capability)
`POST /api/camera/ptz/continuous`
```json
{ "driver": "HIKVISION", "host": "10.206.13.8", "port": 80, "username": "admin", "password": "pass",
  "channel": 1, "pan": 50, "tilt": 0 }
```
Continuous move: `pan`/`tilt` are speeds in −100…100 (negative = left/down, 0 = stop that axis).
`{"pan":0,"tilt":0}` stops all movement. `channel` defaults to 1. → 204.
Drivers without PTZ capability → 400 `BAD_REQUEST` "driver ... does not support PTZ".
A fixed (non-PTZ) camera → the device's error passes through as `INTERNAL`/`BAD_REQUEST` with the
device message. Frontend: hold-to-move arrows overlaid on the live player (mouse/touch down =
move, up/leave = stop; stop is also sent on stream stop and page unload).

## Runtime assumptions
- `ffmpeg` must be on PATH (Windows). Backend fails stream start with `STREAM_ERROR` and message "ffmpeg not found on PATH" if missing.
- Frontend dev server: Vite on port 5173. Backend: Spring Boot on 8080.
