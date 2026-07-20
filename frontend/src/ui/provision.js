// ONVIF setup: status indicator in the Device card + full-screen enable modal.
// Status is read via POST /api/camera/onvif/status (vendor management API);
// enabling + user creation goes through POST /api/camera/onvif/provision.
import { provisionOnvif, getOnvifStatus } from '../api.js';
import { state } from '../state.js';
import { els, escapeHtml } from './dom.js';
import { showError, clearError } from './banner.js';

let getConnection = null; // () => { driver, host, port, username, password }
let getSelectedDriver = null; // () => { id, displayName, canProvisionOnvif }

// ---------- modal ----------

export function openOnvifModal() {
  renderModalStatus();
  els.onvifModal.classList.remove('hidden');
  els.provisionUser.focus();
}

export function closeOnvifModal() {
  els.onvifModal.classList.add('hidden');
  clearProvisionSuggestion();
}

function renderModalStatus() {
  const known = state.onvifEnabled;
  if (known === true) {
    els.modalOnvifStatus.innerHTML =
      '<span class="onvif-pill on">&#10003; ONVIF is ENABLED on this device</span>';
  } else if (known === false) {
    els.modalOnvifStatus.innerHTML =
      '<span class="onvif-pill off">&#10007; ONVIF is DISABLED on this device</span>';
  } else {
    els.modalOnvifStatus.innerHTML =
      '<span class="onvif-pill unknown">ONVIF status unknown — connect to the device first, or just enable it below</span>';
  }
}

// ---------- device-card status row ----------

/**
 * Queries the ONVIF status through the selected driver and renders the
 * indicator in the Device card. Silent on errors (status is best-effort).
 */
export async function checkOnvifStatus() {
  const drv = getSelectedDriver?.();
  if (!drv?.canProvisionOnvif) {
    state.onvifEnabled = undefined;
    els.onvifChip.classList.add('hidden');
    els.deviceOnvif.classList.add('hidden');
    return;
  }
  try {
    const res = await getOnvifStatus(getConnection());
    state.onvifEnabled = res?.supported ? !!res.onvifEnabled : undefined;
  } catch (_) {
    state.onvifEnabled = undefined;
  }
  renderOnvifStatusRow();
}

export function renderOnvifStatusRow() {
  const known = state.onvifEnabled;
  if (known === undefined) {
    els.onvifChip.classList.add('hidden');
    els.deviceOnvif.classList.add('hidden');
    return;
  }

  els.onvifChip.textContent = known ? 'ONVIF: enabled' : 'ONVIF: disabled';
  els.onvifChip.className = `chip onvif ${known ? 'on' : 'off'}`;
  els.onvifChip.classList.remove('hidden');

  if (known) {
    els.deviceOnvif.innerHTML = `
      <div class="onvif-row on">
        <span class="onvif-pill on">&#10003; ONVIF enabled</span>
        <span class="onvif-hint">This device can be used with the Generic ONVIF driver.</span>
      </div>`;
  } else {
    els.deviceOnvif.innerHTML = `
      <div class="onvif-row off">
        <span class="onvif-pill off">&#10007; ONVIF disabled</span>
        <button class="btn accent small" id="open-onvif-modal">Enable ONVIF&hellip;</button>
      </div>`;
    els.deviceOnvif.querySelector('#open-onvif-modal')
      .addEventListener('click', openOnvifModal);
  }
  els.deviceOnvif.classList.remove('hidden');
}

// ---------- suggestion (ONVIF_NOT_ENABLED failure path) ----------

/**
 * Called when a profiles call fails with ONVIF_NOT_ENABLED while a
 * provisioning-capable driver is selected: opens the setup screen directly.
 */
export function suggestProvision() {
  const drv = getSelectedDriver?.();
  if (!drv?.canProvisionOnvif) return;
  state.onvifEnabled = state.onvifEnabled === true ? true : false;
  els.provisionSuggest.textContent =
    'The device did not answer over ONVIF. This driver can enable ONVIF for you — fill in a new ONVIF user and click Enable.';
  els.provisionSuggest.classList.remove('hidden');
  openOnvifModal();
}

export function clearProvisionSuggestion() {
  els.provisionSuggest.classList.add('hidden');
  els.provisionSuggest.textContent = '';
}

// ---------- enable form ----------

function renderSuccess(res) {
  els.provisionResult.innerHTML = `
    <div class="success-panel">
      <div class="success-title">&#10003; ONVIF provisioning succeeded</div>
      <table class="kv">
        <tr><th>Integration</th><td>${escapeHtml(res?.integrationStatus || '—')}</td></tr>
        <tr><th>User</th><td>${escapeHtml(res?.userStatus || '—')}</td></tr>
        ${res?.note ? `<tr><th>Note</th><td>${escapeHtml(res.note)}</td></tr>` : ''}
      </table>
    </div>`;
  els.provisionResult.classList.remove('hidden');
}

export function initProvision({ connection, selectedDriver }) {
  getConnection = connection;
  getSelectedDriver = selectedDriver;

  els.onvifModalClose.addEventListener('click', closeOnvifModal);
  els.onvifModal.addEventListener('click', (e) => {
    if (e.target === els.onvifModal) closeOnvifModal();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !els.onvifModal.classList.contains('hidden')) closeOnvifModal();
  });

  els.provisionForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError();
    const conn = getConnection();
    const onvifUsername = els.provisionUser.value.trim();
    const onvifPassword = els.provisionPass.value;
    if (!conn.host) {
      showError(new Error('Enter the device host in the connection card first.'));
      return;
    }
    if (!onvifUsername || !onvifPassword) return;

    els.provisionBtn.disabled = true;
    els.provisionBtn.textContent = 'Enabling…';
    els.provisionResult.classList.add('hidden');
    els.provisionResult.innerHTML = '';
    try {
      const res = await provisionOnvif({ ...conn, onvifUsername, onvifPassword });
      state.onvifEnabled = true;
      renderSuccess(res);
      renderModalStatus();
      renderOnvifStatusRow();
      clearProvisionSuggestion();
    } catch (err) {
      showError(err); // error envelope surfaces as the global banner
    } finally {
      els.provisionBtn.disabled = false;
      els.provisionBtn.textContent = 'Enable ONVIF on device';
    }
  });
}
