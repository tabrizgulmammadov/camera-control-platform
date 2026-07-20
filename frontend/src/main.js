// App bootstrap & orchestration: mode switching, lazy driver discovery,
// connection forms, and unload cleanup. UI pieces live in src/ui/*.js;
// all backend calls go through src/api.js.
import './style.css';
import { getDrivers, getCameraProfiles, BACKEND_BASE } from './api.js';
import { state, FALLBACK_DRIVERS } from './state.js';
import { initEls, els, escapeHtml } from './ui/dom.js';
import { showError, clearError } from './ui/banner.js';
import { initPlayer, play } from './ui/player.js';
import { initPtz, ptzEmergencyStop } from './ui/ptz.js';
import { renderCameraResults, clearCameraResults } from './ui/profiles.js';
import {
  initProvision,
  checkOnvifStatus,
  suggestProvision,
  clearProvisionSuggestion,
  closeOnvifModal,
} from './ui/provision.js';

initEls(document.getElementById('app'));
initPlayer();
initPtz();

// ---------- Connection helpers ----------
function connectionParams() {
  return {
    driver: selectedDriver().id,
    host: els.$('onvif-host').value.trim(),
    port: parseInt(els.$('onvif-port').value, 10) || 80,
    username: els.$('onvif-user').value.trim(),
    password: els.$('onvif-pass').value,
  };
}

function selectedDriver() {
  const id = els.driverSelect.value || 'ONVIF';
  return (
    (state.drivers || FALLBACK_DRIVERS).find((d) => d.id === id) || { id, displayName: id }
  );
}

initProvision({ connection: connectionParams, selectedDriver });

// ---------- Mode switching (source selector in the connection card) ----------
function setMode(mode) {
  state.mode = mode;
  els.tabRtsp.classList.toggle('active', mode === 'rtsp');
  els.tabOnvif.classList.toggle('active', mode === 'onvif');
  els.tabRtsp.setAttribute('aria-selected', String(mode === 'rtsp'));
  els.tabOnvif.setAttribute('aria-selected', String(mode === 'onvif'));
  els.rtspForm.classList.toggle('hidden', mode !== 'rtsp');
  els.onvifForm.classList.toggle('hidden', mode !== 'onvif');
  const camera = mode === 'onvif';
  els.deviceCard.classList.toggle('hidden', !(camera && state.onvifResult));
  els.profilesCard.classList.toggle('hidden', !(camera && state.onvifResult));
  if (!camera) closeOnvifModal();
  clearError();
  if (camera) loadDrivers();
}
els.tabRtsp.addEventListener('click', () => setMode('rtsp'));
els.tabOnvif.addEventListener('click', () => setMode('onvif'));

// ---------- Driver list (lazy, with fallback for older backends) ----------
async function loadDrivers() {
  if (state.drivers || state.driversLoading) return;
  state.driversLoading = true;
  try {
    const res = await getDrivers();
    const drivers = Array.isArray(res?.drivers) && res.drivers.length ? res.drivers : null;
    if (!drivers) throw new Error('Empty driver list');
    state.drivers = drivers;
    renderDriverOptions();
  } catch (_) {
    // Older backend build without /api/camera/drivers — fall back to plain ONVIF.
    state.drivers = FALLBACK_DRIVERS;
    renderDriverOptions();
    els.driverWarn.textContent =
      'Could not load the driver list from the backend (older build?). Using Generic ONVIF only.';
    els.driverWarn.classList.remove('hidden');
  } finally {
    state.driversLoading = false;
  }
}

function renderDriverOptions() {
  const current = els.driverSelect.value;
  els.driverSelect.innerHTML = state.drivers
    .map((d) => `<option value="${escapeHtml(d.id)}">${escapeHtml(d.displayName || d.id)}</option>`)
    .join('');
  if (state.drivers.some((d) => d.id === current)) els.driverSelect.value = current;
}

els.driverSelect.addEventListener('change', () => {
  clearProvisionSuggestion();
});

// ---------- RTSP mode ----------
els.rtspForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const rtspUrl = els.$('rtsp-url').value.trim();
  if (!rtspUrl) return;
  play({
    rtspUrl,
    username: els.$('rtsp-user').value.trim() || undefined,
    password: els.$('rtsp-pass').value || undefined,
    source: 'rtsp',
  });
});

// ---------- Camera mode: Get Profiles ----------
els.onvifForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (state.busy) return;
  clearError();
  const btn = els.$('onvif-get-btn');
  const driver = selectedDriver();
  state.lastDriver = driver;
  const params = connectionParams();
  btn.disabled = true;
  btn.textContent = 'Querying…';
  try {
    const result = await getCameraProfiles(params);
    state.onvifResult = result;
    state.onvifCreds = { username: params.username, password: params.password };
    state.onvifConn = params; // { driver, host, port, username, password } — reused for PTZ
    clearProvisionSuggestion();
    renderCameraResults();
    // ONVIF status indicator (best-effort, vendor drivers only).
    checkOnvifStatus();
  } catch (err) {
    clearCameraResults();
    showError(err);
    // The selected vendor driver can flip ONVIF on for the user — surface it.
    if (err?.code === 'ONVIF_NOT_ENABLED' && driver.canProvisionOnvif) {
      suggestProvision();
    }
  } finally {
    btn.disabled = false;
    btn.textContent = 'Get Profiles';
  }
});

// Stop backend stream + any in-progress PTZ move when the page unloads (best effort).
window.addEventListener('beforeunload', () => {
  ptzEmergencyStop();
  if (state.active?.streamId) {
    try {
      fetch(`${BACKEND_BASE}/api/stream/${encodeURIComponent(state.active.streamId)}`, {
        method: 'DELETE',
        keepalive: true,
      });
    } catch (_) {}
  }
});

setMode('rtsp');
