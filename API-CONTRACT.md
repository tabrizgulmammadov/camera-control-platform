# Camera Control Platform — API Contract

This document summarizes the public HTTP API exposed by the backend. It mirrors the request/response DTOs in the server code (see `backend/src/main/java/com/cameracontrolplatform/api/dto`).

Error envelope

- All error responses use the envelope:

```
{ "code": "<ERROR_CODE>", "message": "human friendly message" }
```

Shared error codes

- ONVIF_NOT_ENABLED
- ONVIF_AUTH_FAILED
- DEVICE_UNREACHABLE
- STREAM_ERROR
- BAD_REQUEST
- NOT_FOUND
- INTERNAL

Endpoints

1) GET /api/camera/drivers

- Response: `DriversResponse`

Example:

```
{
  "drivers": [ { "id": "ONVIF", "displayName": "ONVIF", "canProvisionOnvif": true }, ... ]
}
```

2) POST /api/camera/onvif/status

- Request: `OnvifProvisionRequest` (driver, host, port, username, password)
- Response: { supported: boolean, onvifEnabled: boolean | null }

3) POST /api/camera/onvif/provision

- Request: `OnvifProvisionRequest`
- Response: `OnvifProvisionResponse` { integrationStatus, userStatus, note }

4) POST /api/camera/ptz/continuous

- Request: `PtzContinuousRequest` (driver, host, port, username, password, channel, pan, tilt)
- Response: 204 No Content on success

5) POST /api/camera/profiles

- Request: `CameraProfilesRequest` (driver, host, port, username, password)
- Response: `CameraProfilesResponse` {
    deviceInfo: { manufacturer, model, firmwareVersion, serialNumber },
    profiles: [ {
      token, name, streamType, rtspUri,
      videoEncoder: { encoding, resolution: {width,height}, frameRate, bitrateKbps, quality, govLength, profile },
      audioEncoder: { encoding, bitrateKbps, sampleRateKhz }
    } ]
  }

6) POST /api/onvif/profiles

- Legacy alias. Behaves exactly like `POST /api/camera/profiles` with driver="ONVIF".

7) POST /api/stream/start

- Request: `StreamStartRequest` { rtspUrl, username, password }
- Response: `StreamStartResponse` { streamId, whepUrl, transport = "WEBRTC", details: { rtspUrl, startedAt } }

8) GET /api/stream/{streamId}

- Response: `StreamDetailsResponse` { streamId, running, rtspUrl, startedAt, error }

9) DELETE /api/stream/{streamId}

- Stops the stream. Response: 204 No Content

Notes

- For full request/response field names and types, see the Java DTOs in `backend/src/main/java/com/cameracontrolplatform/api/dto`.
- Frontend and tests depend on this file being present; creating this file restores the referenced documentation link.
