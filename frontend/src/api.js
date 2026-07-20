// API client conforming to API-CONTRACT.md
// Overridable for test/dev setups: VITE_BACKEND_BASE=http://localhost:8081 npm run dev
export const BACKEND_BASE = import.meta.env?.VITE_BACKEND_BASE || 'http://localhost:8080';

export class ApiError extends Error {
  constructor(code, message, status) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

export class BackendDownError extends Error {
  constructor() {
    super(`Backend not reachable at ${BACKEND_BASE}`);
    this.code = 'BACKEND_DOWN';
  }
}

async function request(path, options = {}) {
  let res;
  try {
    res = await fetch(`${BACKEND_BASE}${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    });
  } catch (_) {
    throw new BackendDownError();
  }
  if (res.status === 204) return null;
  if (!res.ok) {
    let body = null;
    try {
      body = await res.json();
    } catch (_) {
      /* non-JSON error body */
    }
    throw new ApiError(
      body?.code || 'INTERNAL',
      body?.message || `Request failed with HTTP ${res.status}`,
      res.status
    );
  }
  return res.json();
}

/**
 * Driver discovery. Each driver carries a `canProvisionOnvif` capability flag
 * (older backends omit it — normalized to false here).
 */
export async function getDrivers() {
  const res = await request('/api/camera/drivers');
  const drivers = Array.isArray(res?.drivers)
    ? res.drivers.map((d) => ({
        id: d.id,
        displayName: d.displayName || d.id,
        canProvisionOnvif: !!d.canProvisionOnvif,
      }))
    : [];
  return { drivers };
}

/**
 * Ask a provisioning-capable vendor driver (e.g. Hikvision/ISAPI) to enable
 * the ONVIF integration protocol on the device and create/update an ONVIF user.
 * Resolves { integrationStatus, userStatus, note }.
 */
/**
 * Ask a provisioning-capable driver whether ONVIF is currently enabled on the
 * device. Resolves { supported, onvifEnabled }.
 */
export function getOnvifStatus({ driver, host, port, username, password }) {
  return request('/api/camera/onvif/status', {
    method: 'POST',
    body: JSON.stringify({ driver, host, port, username, password }),
  });
}

export function provisionOnvif({ driver, host, port, username, password, onvifUsername, onvifPassword }) {
  return request('/api/camera/onvif/provision', {
    method: 'POST',
    body: JSON.stringify({ driver, host, port, username, password, onvifUsername, onvifPassword }),
  });
}

export function getCameraProfiles({ driver, host, port, username, password }) {
  return request('/api/camera/profiles', {
    method: 'POST',
    body: JSON.stringify({ driver, host, port, username, password }),
  });
}

export function startStream({ rtspUrl, username, password }) {
  const body = { rtspUrl };
  if (username) body.username = username;
  if (password) body.password = password;
  return request('/api/stream/start', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/**
 * Continuous PTZ move (driver capability). pan/tilt are speeds in −100…100
 * (negative = left/down); {pan:0,tilt:0} stops all movement. Resolves null (204).
 */
export function ptzContinuous({ driver, host, port, username, password, channel = 1, pan = 0, tilt = 0 }) {
  return request('/api/camera/ptz/continuous', {
    method: 'POST',
    body: JSON.stringify({ driver, host, port, username, password, channel, pan, tilt }),
  });
}

export function stopStream(streamId) {
  return request(`/api/stream/${encodeURIComponent(streamId)}`, { method: 'DELETE' });
}

export function getStreamDetails(streamId) {
  return request(`/api/stream/${encodeURIComponent(streamId)}`);
}
