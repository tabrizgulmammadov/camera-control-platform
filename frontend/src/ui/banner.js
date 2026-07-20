// Global error banner (driver-aware ONVIF_NOT_ENABLED wording, backend-down, etc.)
import { BackendDownError } from '../api.js';
import { state } from '../state.js';
import { els, escapeHtml } from './dom.js';

export function showError(err) {
  let msg;
  if (err instanceof BackendDownError) {
    msg = err.message;
  } else if (err?.code === 'ONVIF_NOT_ENABLED') {
    const drv = state.lastDriver;
    if (drv && drv.id !== 'ONVIF') {
      // e.g. Hikvision (ISAPI) → "ISAPI is not answering on this device/port."
      const proto = /\(([^)]+)\)/.exec(drv.displayName || '')?.[1] || drv.displayName || drv.id;
      msg = `${proto} is not answering on this device/port. ` + (err.message || '');
    } else {
      msg = 'ONVIF is not enabled on this device or the port is wrong. ' + (err.message || '');
    }
  } else if (err?.code) {
    msg = `${err.code}: ${err.message}`;
  } else {
    msg = err?.message || String(err);
  }
  els.errorBanner.innerHTML = `<span class="error-icon">&#9888;</span><span>${escapeHtml(msg)}</span>
    <button class="banner-close" title="Dismiss">&times;</button>`;
  els.errorBanner.querySelector('.banner-close').addEventListener('click', clearError);
  els.errorBanner.classList.remove('hidden');
}

export function clearError() {
  els.errorBanner.classList.add('hidden');
  els.errorBanner.innerHTML = '';
}
