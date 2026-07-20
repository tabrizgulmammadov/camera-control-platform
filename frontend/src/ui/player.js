// Player + stream lifecycle: start/stop, playlist wait with health polling,
// bounded hls.js retry, live health in the details panel.
// The play/stop/waitForPlaylist/attachHls logic is carried over unchanged from
// the pre-redesign main.js (well-tested) — only rendering hooks are new.
import Hls from 'hls.js';
import { startStream, stopStream, waitForPlaylist, getStreamDetails } from '../api.js';
import { state } from '../state.js';
import { els, escapeHtml, fmtTime, encoderSummary } from './dom.js';
import { showError, clearError } from './banner.js';
import { highlightPlayingProfile } from './profiles.js';
import { showPtzFor, hidePtz } from './ptz.js';

const MAX_FATAL_RECOVERIES = 3;
const HEALTH_POLL_MS = 5000;
const WHEP_ICE_TIMEOUT_MS = 1000;
const WHEP_FIRST_FRAME_TIMEOUT_MS = 4000;
let healthTimer = null;

// ---------- Status chip / overlay ----------
function setStatus(text, cls) {
  els.playerStatus.textContent = text;
  els.playerStatus.className = `status-chip ${cls}`;
}

function setOverlay(text) {
  if (text) {
    els.overlay.textContent = text;
    els.overlay.classList.remove('hidden');
  } else {
    els.overlay.classList.add('hidden');
  }
}

// ---------- Teardown ----------
function closePeer() {
  if (state.pc) {
    try {
      state.pc.close();
    } catch (_) {}
    state.pc = null;
  }
  // Release the WHEP session on the relay (best effort, per the WHEP spec).
  const resource = state.active?.whepResource;
  if (resource) {
    state.active.whepResource = null;
    fetch(resource, { method: 'DELETE' }).catch(() => {});
  }
  els.video.srcObject = null;
}

function destroyPlayer() {
  closePeer();
  if (state.hls) {
    state.hls.destroy();
    state.hls = null;
  }
  els.video.pause();
  els.video.removeAttribute('src');
  els.video.load();
}

export async function stopActiveStream() {
  stopHealthPoll();
  await hidePtz(); // guarantees a final PTZ {0,0} if the camera is moving
  destroyPlayer();
  const active = state.active;
  state.active = null;
  renderDetails();
  setOverlay('No stream playing');
  setStatus('Idle', 'idle');
  els.stopBtn.classList.add('hidden');
  if (active?.streamId) {
    try {
      await stopStream(active.streamId);
    } catch (_) {
      // Best effort — backend auto-reaps idle streams.
    }
  }
  highlightPlayingProfile(null);
}

// ---------- Live health polling (details panel) ----------
function stopHealthPoll() {
  if (healthTimer) {
    clearInterval(healthTimer);
    healthTimer = null;
  }
}

function startHealthPoll(streamId) {
  stopHealthPoll();
  healthTimer = setInterval(async () => {
    if (state.active?.streamId !== streamId) {
      stopHealthPoll();
      return;
    }
    try {
      const h = await getStreamDetails(streamId);
      if (state.active?.streamId !== streamId) return;
      state.active.health = h;
      renderDetails();
      if (h.running === false) {
        stopHealthPoll();
        setStatus('Stopped', 'error');
        showError(new Error(`Stream stopped: ${h.error || 'the ffmpeg process exited.'}`));
      }
    } catch (err) {
      if (err?.status === 404 && state.active?.streamId === streamId) {
        // Backend reaped the stream (idle >5 min) or restarted.
        stopHealthPoll();
        state.active.health = { running: false, ffmpegAlive: false, error: 'Stream no longer exists on the backend.' };
        renderDetails();
      }
      // Other failures: best effort, keep polling.
    }
  }, HEALTH_POLL_MS);
}

// ---------- WHEP (WebRTC-HTTP egress) client ----------
function waitForIceGathering(pc, timeoutMs) {
  if (pc.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(done, timeoutMs);
    function done() {
      clearTimeout(timer);
      pc.removeEventListener('icegatheringstatechange', check);
      resolve();
    }
    function check() {
      if (pc.iceGatheringState === 'complete') done();
    }
    pc.addEventListener('icegatheringstatechange', check);
  });
}

function waitForFirstFrame(videoEl, pc, timeoutMs) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (ok, err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      pc.removeEventListener('connectionstatechange', onState);
      videoEl.removeEventListener('loadeddata', onFrame);
      ok ? resolve() : reject(err);
    };
    const timer = setTimeout(
      () => finish(false, new Error(`no video frame within ${Math.round(timeoutMs / 1000)} s`)),
      timeoutMs
    );
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

/**
 * Standard WHEP handshake: recvonly offer → POST application/sdp → answer.
 * Resolves { pc, resource } once the first video frame rendered; on any
 * failure the peer connection is closed and the error propagates (caller
 * falls back to HLS).
 */
async function whepConnect(whepUrl, videoEl) {
  const pc = new RTCPeerConnection();
  try {
    const mediaStream = new MediaStream();
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    pc.addEventListener('track', (evt) => {
      mediaStream.addTrack(evt.track);
      if (videoEl.srcObject !== mediaStream) {
        videoEl.srcObject = mediaStream;
        videoEl.play().catch(() => {});
      }
    });
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    await waitForIceGathering(pc, WHEP_ICE_TIMEOUT_MS);
    const res = await fetch(whepUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/sdp' },
      body: pc.localDescription.sdp,
    });
    if (!res.ok) throw new Error(`WHEP endpoint answered HTTP ${res.status}`);
    const location = res.headers.get('Location');
    const answerSdp = await res.text();
    await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
    await waitForFirstFrame(videoEl, pc, WHEP_FIRST_FRAME_TIMEOUT_MS);
    return { pc, resource: location ? new URL(location, whepUrl).toString() : null };
  } catch (err) {
    try {
      pc.close();
    } catch (_) {}
    if (videoEl.srcObject) videoEl.srcObject = null;
    throw err;
  }
}

// ---------- hls.js attach (bounded fatal-error retry) ----------
function attachHls(hlsUrl) {
  if (Hls.isSupported()) {
    const hls = new Hls({ liveDurationInfinity: true });
    state.hls = hls;
    let recoveries = 0;
    hls.loadSource(hlsUrl);
    hls.attachMedia(els.video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      els.video.play().catch(() => {});
      setOverlay(null);
      setStatus('Live · HLS', 'live');
    });
    hls.on(Hls.Events.FRAG_LOADED, () => {
      recoveries = 0; // healthy again — reset the retry budget
    });
    hls.on(Hls.Events.ERROR, (_evt, data) => {
      if (!data.fatal) return;
      recoveries += 1;
      if (recoveries > MAX_FATAL_RECOVERIES) {
        // Do not retry forever (e.g. backend reaped the stream and the
        // playlist now 404s) — surface the failure and tear down.
        showError(new Error('Playback failed: the stream is no longer available.'));
        stopActiveStream();
        return;
      }
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
        hls.startLoad();
      } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError();
      } else {
        showError(new Error('Playback failed (fatal hls.js error).'));
        stopActiveStream();
      }
    });
  } else if (els.video.canPlayType('application/vnd.apple.mpegurl')) {
    els.video.src = hlsUrl;
    els.video.play().catch(() => {});
    setOverlay(null);
    setStatus('Live · HLS', 'live');
  } else {
    showError(new Error('HLS playback is not supported in this browser.'));
  }
}

/**
 * Start playback of an RTSP url via the backend.
 * source: 'rtsp' | 'onvif'; profile: optional camera profile object.
 * Only one stream is active at a time; starting a new one stops the previous.
 */
export async function play({ rtspUrl, username, password, source, profile }) {
  if (state.busy) return;
  state.busy = true;
  clearError();
  try {
    // Only one active player at a time.
    await stopActiveStream();

    setOverlay('Starting stream…');
    setStatus('Starting…', 'busy');
    const res = await startStream({ rtspUrl, username, password });

    state.active = {
      streamId: res.streamId,
      hlsUrl: res.hlsUrl,
      whepUrl: res.whepUrl || null,
      whepResource: null,
      transport: null, // set once playback is attached
      transportNote: null, // fallback reason (shown in the details panel)
      rtspUrl: res.details?.rtspUrl || rtspUrl,
      startedAt: res.details?.startedAt || new Date().toISOString(),
      source,
      profile: profile || null,
      health: null,
    };
    renderDetails();
    els.stopBtn.classList.remove('hidden');
    highlightPlayingProfile(profile?.token || null);

    // ---- WebRTC first (low latency) when the backend advertises a WHEP url ----
    let attached = false;
    if (res.whepUrl) {
      setOverlay('Connecting WebRTC…');
      try {
        const { pc, resource } = await whepConnect(res.whepUrl, els.video);
        if (state.active?.streamId !== res.streamId) {
          // User stopped/restarted during the handshake.
          try { pc.close(); } catch (_) {}
          if (resource) fetch(resource, { method: 'DELETE' }).catch(() => {});
          return;
        }
        state.pc = pc;
        state.active.whepResource = resource;
        state.active.transport = 'WEBRTC';
        setOverlay(null);
        setStatus('Live · WebRTC', 'live');
        attached = true;
      } catch (err) {
        if (state.active?.streamId !== res.streamId) return;
        state.active.transportNote = `WebRTC (WHEP) failed: ${err?.message || err} — fell back to HLS.`;
      }
    }

    // ---- HLS path (primary without MediaMTX, fallback otherwise) ----
    if (!attached) {
      state.active.transport = 'HLS';
      setOverlay('Waiting for HLS playlist…');
      const { ready, error } = await waitForPlaylist(res.hlsUrl, {
        // Abort polling early if the user stopped / restarted meanwhile.
        isCancelled: () => state.active?.streamId !== res.streamId,
        // Poll backend stream health so ffmpeg failures surface immediately.
        streamId: res.streamId,
      });
      if (state.active?.streamId !== res.streamId) return;
      if (!ready) {
        showError(new Error(error
          ? `Stream failed to start: ${error}`
          : 'Stream did not become ready within 15 s. Check the RTSP URL / credentials.'));
        await stopActiveStream();
        return;
      }
      attachHls(res.hlsUrl);
    }

    renderDetails();
    startHealthPoll(res.streamId);

    // PTZ overlay — only for streams started from a camera profile, where the
    // device connection params are known (hidden for plain RTSP-URL streams).
    if (source === 'onvif' && state.onvifConn && profile) {
      showPtzFor(state.onvifConn, profile);
      els.videoWrap.focus({ preventScroll: true });
    }
  } catch (err) {
    showError(err);
    setOverlay('No stream playing');
    setStatus('Idle', 'idle');
  } finally {
    state.busy = false;
  }
}

// ---------- Details panel (incl. live health) ----------
export function renderDetails() {
  const a = state.active;
  if (!a) {
    els.details.classList.add('hidden');
    els.details.innerHTML = '';
    return;
  }
  const rows = [];
  if (a.profile) {
    rows.push(['Profile', `${escapeHtml(a.profile.name)} <span class="dim">(${escapeHtml(a.profile.token)})</span>`]);
    if (a.profile.videoEncoder) rows.push(['Encoder', encoderSummary(a.profile.videoEncoder)]);
  }
  rows.push(['Stream ID', `<span class="mono">${escapeHtml(a.streamId)}</span>`]);
  if (a.transport) {
    rows.push(['Transport', a.transport === 'WEBRTC'
      ? '<span class="transport webrtc">WEBRTC</span> <span class="dim">low-latency (WHEP)</span>'
      : '<span class="transport hls">HLS</span>']);
  }
  if (a.transportNote) {
    rows.push(['Fallback', `<span class="dim">${escapeHtml(a.transportNote)}</span>`]);
  }
  rows.push(['RTSP URL', `<span class="mono">${escapeHtml(a.rtspUrl)}</span>`]);
  if (a.transport === 'WEBRTC' && a.whepUrl) {
    rows.push(['WHEP URL', `<span class="mono">${escapeHtml(a.whepUrl)}</span>`]);
  }
  rows.push(['HLS URL', `<span class="mono">${escapeHtml(a.hlsUrl)}</span>`]);
  rows.push(['Started', escapeHtml(fmtTime(a.startedAt))]);

  const h = a.health;
  if (h) {
    const ok = h.running !== false && h.ffmpegAlive !== false;
    rows.push(['Health', ok
      ? '<span class="health ok"><span class="dot"></span>Running — ffmpeg alive</span>'
      : '<span class="health bad"><span class="dot"></span>Stopped</span>']);
    if (!ok && h.error) {
      rows.push(['Error', `<span class="health-error mono">${escapeHtml(h.error)}</span>`]);
    }
  }

  els.details.innerHTML = `
    <h3>Stream details</h3>
    <table class="kv">
      ${rows.map(([k, v]) => `<tr><th>${k}</th><td>${v}</td></tr>`).join('')}
    </table>`;
  els.details.classList.remove('hidden');
}

export function initPlayer() {
  els.stopBtn.addEventListener('click', () => stopActiveStream());
}
