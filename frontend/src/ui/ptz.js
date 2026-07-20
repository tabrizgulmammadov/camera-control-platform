// PTZ hold-to-move overlay on the live player (camera-mode streams only).
// pointerdown / arrow-keydown = continuous move, release = stop. Requests are
// serialized: while one is in flight the latest desired speed is remembered and
// sent when the response returns, so the final stop is always delivered.
import { ptzContinuous, BACKEND_BASE } from '../api.js';
import { els } from './dom.js';

const SPEED = 40; // pan/tilt speed magnitude (−100…100 scale)
const NOTE_HIDE_MS = 5000;

let session = null; // { conn: {driver,host,port,username,password}, channel } for the active stream
let desired = { pan: 0, tilt: 0 };
let lastSent = { pan: 0, tilt: 0 };
let inFlight = false;
let noteTimer = null;

/**
 * Derive the PTZ channel from a camera profile (default 1).
 * Hikvision stream ids encode the channel: 101/102 → channel 1, 201 → 2.
 * Looks at the profile token first, then the RTSP URI path.
 */
export function ptzChannelFromProfile(profile) {
  const token = String(profile?.token || '');
  const uri = String(profile?.rtspUri || '');
  const m = token.match(/^(\d{1,2})0\d$/) || uri.match(/\/[Cc]hannels\/(\d{1,2})0\d(?:\D|$)/);
  if (m) return parseInt(m[1], 10);
  return 1;
}

// ---------- request pump (one in-flight request, latest-wins) ----------
async function pump() {
  if (inFlight || !session) return;
  if (desired.pan === lastSent.pan && desired.tilt === lastSent.tilt) return;
  inFlight = true;
  const target = { ...desired };
  const s = session;
  try {
    await ptzContinuous({ ...s.conn, channel: s.channel, pan: target.pan, tilt: target.tilt });
    lastSent = target;
  } catch (err) {
    lastSent = target; // don't retry-loop a failing device
    markUnavailable(err);
  } finally {
    inFlight = false;
    pump(); // deliver the latest desired speed (e.g. the release → stop)
  }
}

function setDesired(pan, tilt) {
  if (!session) return;
  desired = { pan, tilt };
  pump();
}

function markUnavailable(err) {
  // 400 "does not support PTZ" envelope or a device error: one-time note,
  // controls hidden for the rest of this stream.
  session = null;
  desired = { pan: 0, tilt: 0 };
  els.ptzPad.classList.add('hidden');
  const detail = err?.code === 'BACKEND_DOWN' ? 'backend not reachable' : err?.message;
  els.ptzNote.textContent = 'PTZ not available for this camera/driver' + (detail ? ` — ${detail}` : '');
  els.ptzNote.classList.remove('hidden');
  clearTimeout(noteTimer);
  noteTimer = setTimeout(() => els.ptzNote.classList.add('hidden'), NOTE_HIDE_MS);
}

// ---------- lifecycle (called by the player) ----------
/** Show the overlay for a camera-mode stream. conn = connection params, profile for channel derivation. */
export function showPtzFor(conn, profile) {
  session = { conn, channel: ptzChannelFromProfile(profile) };
  desired = { pan: 0, tilt: 0 };
  lastSent = { pan: 0, tilt: 0 };
  els.ptzNote.classList.add('hidden');
  els.ptzPad.classList.remove('hidden');
}

/** Hide the overlay; guarantees a final {pan:0,tilt:0} if the camera may still be moving. */
export async function hidePtz() {
  const s = session;
  session = null;
  els.ptzPad.classList.add('hidden');
  els.ptzNote.classList.add('hidden');
  clearTimeout(noteTimer);
  const moving = lastSent.pan !== 0 || lastSent.tilt !== 0 || inFlight;
  desired = { pan: 0, tilt: 0 };
  lastSent = { pan: 0, tilt: 0 };
  if (s && moving) {
    try {
      await ptzContinuous({ ...s.conn, channel: s.channel, pan: 0, tilt: 0 });
    } catch (_) {
      /* best effort */
    }
  }
}

/** Synchronous best-effort stop for beforeunload (keepalive fetch). */
export function ptzEmergencyStop() {
  const s = session;
  if (!s) return;
  if (lastSent.pan === 0 && lastSent.tilt === 0 && !inFlight) return;
  try {
    fetch(`${BACKEND_BASE}/api/camera/ptz/continuous`, {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...s.conn, channel: s.channel, pan: 0, tilt: 0 }),
    });
  } catch (_) {}
}

// ---------- input wiring ----------
export function initPtz() {
  els.ptzPad.querySelectorAll('.ptz-btn').forEach((btn) => {
    const pan = Number(btn.dataset.pan) * SPEED;
    const tilt = Number(btn.dataset.tilt) * SPEED;
    const start = (e) => {
      e.preventDefault();
      btn.setPointerCapture?.(e.pointerId);
      btn.classList.add('active');
      setDesired(pan, tilt);
    };
    const stop = () => {
      btn.classList.remove('active');
      setDesired(0, 0);
    };
    btn.addEventListener('pointerdown', start);
    btn.addEventListener('pointerup', stop);
    btn.addEventListener('pointerleave', stop);
    btn.addEventListener('pointercancel', stop);
    // Don't let a click steal focus from the player / trigger anything else.
    btn.addEventListener('click', (e) => e.preventDefault());
    btn.addEventListener('contextmenu', (e) => e.preventDefault());
  });

  // Keyboard: arrow keys while the player has focus, keyup = stop.
  const KEYS = {
    ArrowLeft: { pan: -SPEED, tilt: 0 },
    ArrowRight: { pan: SPEED, tilt: 0 },
    ArrowUp: { pan: 0, tilt: SPEED },
    ArrowDown: { pan: 0, tilt: -SPEED },
  };
  els.videoWrap.addEventListener('keydown', (e) => {
    const k = KEYS[e.key];
    if (!k || !session) return;
    e.preventDefault();
    if (e.repeat) return;
    setDesired(k.pan, k.tilt);
  });
  els.videoWrap.addEventListener('keyup', (e) => {
    if (!KEYS[e.key] || !session) return;
    e.preventDefault();
    setDesired(0, 0);
  });
  els.videoWrap.addEventListener('blur', () => setDesired(0, 0));
}
