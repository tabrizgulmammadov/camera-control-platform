// WebRTC/WHEP-only player and stream lifecycle.
import { startStream, stopStream, getStreamDetails } from '../api.js';
import { state } from '../state.js';
import { els, escapeHtml, fmtTime, encoderSummary } from './dom.js';
import { showError, clearError } from './banner.js';
import { highlightPlayingProfile } from './profiles.js';
import { showPtzFor, hidePtz } from './ptz.js';

const HEALTH_POLL_MS = 5000;
const WHEP_ICE_TIMEOUT_MS = 1000;
const WHEP_FIRST_FRAME_TIMEOUT_MS = 4000;
let healthTimer = null;

function setStatus(text, cls) {
  els.playerStatus.textContent = text;
  els.playerStatus.className = `status-chip ${cls}`;
}

function setOverlay(text) {
  els.overlay.textContent = text || '';
  els.overlay.classList.toggle('hidden', !text);
}

function closePeer() {
  if (state.pc) {
    try { state.pc.close(); } catch (_) {}
    state.pc = null;
  }
  const resource = state.active?.whepResource;
  if (resource) {
    state.active.whepResource = null;
    fetch(resource, { method: 'DELETE' }).catch(() => {});
  }
  els.video.srcObject = null;
}

function destroyPlayer() {
  closePeer();
  els.video.pause();
  els.video.removeAttribute('src');
  els.video.load();
}

export async function stopActiveStream() {
  stopHealthPoll();
  await hidePtz();
  destroyPlayer();
  const active = state.active;
  state.active = null;
  renderDetails();
  setOverlay('No stream playing');
  setStatus('Idle', 'idle');
  els.stopBtn.classList.add('hidden');
  if (active?.streamId) {
    try { await stopStream(active.streamId); } catch (_) {}
  }
  highlightPlayingProfile(null);
}

function stopHealthPoll() {
  if (healthTimer) clearInterval(healthTimer);
  healthTimer = null;
}

function startHealthPoll(streamId) {
  stopHealthPoll();
  healthTimer = setInterval(async () => {
    if (state.active?.streamId !== streamId) return stopHealthPoll();
    try {
      const health = await getStreamDetails(streamId);
      if (state.active?.streamId !== streamId) return;
      state.active.health = health;
      renderDetails();
      if (!health.running) {
        stopHealthPoll();
        setStatus('Stopped', 'error');
        showError(new Error(health.error || 'MediaMTX is unavailable.'));
      }
    } catch (_) {
      // The next poll may recover after a transient backend request failure.
    }
  }, HEALTH_POLL_MS);
}

function waitForIceGathering(pc, timeoutMs) {
  if (pc.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(done, timeoutMs);
    function done() {
      clearTimeout(timer);
      pc.removeEventListener('icegatheringstatechange', check);
      resolve();
    }
    function check() { if (pc.iceGatheringState === 'complete') done(); }
    pc.addEventListener('icegatheringstatechange', check);
  });
}

function waitForFirstFrame(videoEl, pc, timeoutMs) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (ok, error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      pc.removeEventListener('connectionstatechange', onState);
      videoEl.removeEventListener('loadeddata', onFrame);
      ok ? resolve() : reject(error);
    };
    const timer = setTimeout(() => finish(false, new Error('no video frame within 4 s')), timeoutMs);
    const onState = () => {
      if (['failed', 'closed'].includes(pc.connectionState)) {
        finish(false, new Error(`peer connection ${pc.connectionState}`));
      }
    };
    const onFrame = () => finish(true);
    pc.addEventListener('connectionstatechange', onState);
    if (typeof videoEl.requestVideoFrameCallback === 'function') {
      videoEl.requestVideoFrameCallback(() => finish(true));
    } else {
      videoEl.addEventListener('loadeddata', onFrame);
    }
  });
}

async function whepConnect(whepUrl, videoEl) {
  const pc = new RTCPeerConnection();
  try {
    const mediaStream = new MediaStream();
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    pc.addEventListener('track', (event) => {
      mediaStream.addTrack(event.track);
      if (videoEl.srcObject !== mediaStream) {
        videoEl.srcObject = mediaStream;
        videoEl.play().catch(() => {});
      }
    });
    await pc.setLocalDescription(await pc.createOffer());
    await waitForIceGathering(pc, WHEP_ICE_TIMEOUT_MS);
    const response = await fetch(whepUrl, {
      method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: pc.localDescription.sdp,
    });
    if (!response.ok) throw new Error(`WHEP endpoint answered HTTP ${response.status}`);
    const location = response.headers.get('Location');
    await pc.setRemoteDescription({ type: 'answer', sdp: await response.text() });
    await waitForFirstFrame(videoEl, pc, WHEP_FIRST_FRAME_TIMEOUT_MS);
    return { pc, resource: location ? new URL(location, whepUrl).toString() : null };
  } catch (error) {
    try { pc.close(); } catch (_) {}
    videoEl.srcObject = null;
    throw error;
  }
}

export async function play({ rtspUrl, username, password, profile }) {
  if (state.busy) return;
  state.busy = true;
  clearError();
  try {
    await stopActiveStream();
    setOverlay('Starting WebRTC stream...');
    setStatus('Starting...', 'busy');
    const response = await startStream({ rtspUrl, username, password });
    state.active = {
      streamId: response.streamId,
      whepUrl: response.whepUrl,
      whepResource: null,
      rtspUrl: response.details?.rtspUrl || rtspUrl,
      startedAt: response.details?.startedAt || new Date().toISOString(),
      profile: profile || null, health: null,
    };
    const { pc, resource } = await whepConnect(response.whepUrl, els.video);
    if (state.active?.streamId !== response.streamId) {
      try { pc.close(); } catch (_) {}
      if (resource) fetch(resource, { method: 'DELETE' }).catch(() => {});
      return;
    }
    state.pc = pc;
    state.active.whepResource = resource;
    setOverlay(null);
    setStatus('Live - WebRTC', 'live');
    els.stopBtn.classList.remove('hidden');
    highlightPlayingProfile(profile?.token || null);
    renderDetails();
    startHealthPoll(response.streamId);
    if (state.onvifConn && profile) {
      showPtzFor(state.onvifConn, profile);
      els.videoWrap.focus({ preventScroll: true });
    }
  } catch (error) {
    showError(error);
    setOverlay('No stream playing');
    setStatus('Idle', 'idle');
    if (state.active?.streamId) await stopActiveStream();
  } finally {
    state.busy = false;
  }
}

export function renderDetails() {
  const active = state.active;
  if (!active) {
    els.details.classList.add('hidden');
    els.details.innerHTML = '';
    return;
  }
  const rows = [];
  if (active.profile) {
    rows.push(['Profile', `${escapeHtml(active.profile.name)} <span class="dim">(${escapeHtml(active.profile.token)})</span>`]);
    if (active.profile.videoEncoder) rows.push(['Encoder', encoderSummary(active.profile.videoEncoder)]);
  }
  rows.push(['Stream ID', `<span class="mono">${escapeHtml(active.streamId)}</span>`]);
  rows.push(['Transport', '<span class="transport webrtc">WEBRTC</span> <span class="dim">WHEP</span>']);
  rows.push(['RTSP URL', `<span class="mono">${escapeHtml(active.rtspUrl)}</span>`]);
  rows.push(['WHEP URL', `<span class="mono">${escapeHtml(active.whepUrl)}</span>`]);
  rows.push(['Started', escapeHtml(fmtTime(active.startedAt))]);
  if (active.health) {
    rows.push(['Health', active.health.running
      ? '<span class="health ok"><span class="dot"></span>MediaMTX available</span>'
      : `<span class="health bad"><span class="dot"></span>${escapeHtml(active.health.error || 'Unavailable')}</span>`]);
  }
  els.details.innerHTML = `<h3>Stream details</h3><table class="kv">${rows.map(([k, v]) => `<tr><th>${k}</th><td>${v}</td></tr>`).join('')}</table>`;
  els.details.classList.remove('hidden');
}

export function initPlayer() {
  els.stopBtn.addEventListener('click', () => stopActiveStream());
}
