// Shared mutable app state (single source of truth across ui modules).
export const state = {
  mode: 'rtsp', // 'rtsp' | 'onvif'
  active: null, // { streamId, whepUrl, whepResource, startedAt, rtspUrl, source, profile, health }
  pc: null, // RTCPeerConnection while playing over WebRTC (WHEP)
  onvifResult: null, // { deviceInfo, profiles }
  onvifCreds: null, // { username, password } used for last profile fetch
  onvifConn: null, // { driver, host, port, username, password } of last successful profile fetch (PTZ)
  drivers: null, // [{ id, displayName, canProvisionOnvif }] — lazily fetched
  driversLoading: false,
  lastDriver: null, // driver used for the last profile fetch (banner wording)
  busy: false,
};

export const FALLBACK_DRIVERS = [
  { id: 'ONVIF', displayName: 'Generic ONVIF', canProvisionOnvif: false },
];
