// DOM skeleton for the two-column workspace + shared element refs & format helpers.
import { BACKEND_BASE } from '../api.js';

export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

export function fmtTime(iso) {
  try {
    return new Date(iso).toLocaleString();
  } catch (_) {
    return iso;
  }
}

/** One-line summary of a videoEncoder object (already HTML-escaped). */
export function encoderSummary(ve) {
  if (!ve) return '';
  const parts = [];
  if (ve.encoding) parts.push(escapeHtml(ve.encoding));
  if (ve.resolution) parts.push(`${ve.resolution.width}×${ve.resolution.height}`);
  if (ve.frameRate != null) parts.push(`${ve.frameRate} fps`);
  if (ve.bitrateKbps != null) parts.push(`${ve.bitrateKbps} kbps`);
  return parts.join(' · ');
}

export function buildLayout(app) {
  app.innerHTML = `
  <header class="topbar">
    <div class="brand">
      <span class="brand-dot"></span>
      <h1>Camera Control Platform</h1>
      <span class="brand-sub">ONVIF / manufacturer driver operations</span>
    </div>
    <span class="chip mono" title="Backend base URL">${escapeHtml(BACKEND_BASE.replace(/^https?:\/\//, ''))}</span>
  </header>

  <div id="error-banner" class="error-banner hidden" role="alert"></div>

  <main class="workspace">
    <!-- ========== Left column: connection & device ========== -->
    <div class="col col-left">
      <section class="card" id="connection-card">
        <div class="card-head">
          <h2>Camera Connection</h2>
        </div>
        <div class="card-body">
          <form id="onvif-form" class="form">
            <label>Manufacturer / Driver
              <select id="onvif-driver">
                <option value="ONVIF">Generic ONVIF</option>
              </select>
            </label>
            <div id="driver-warn" class="note warn hidden"></div>
            <div class="row2">
              <label>Host
                <input id="onvif-host" type="text" placeholder="192.168.1.64" required autocomplete="off" spellcheck="false" />
              </label>
              <label>Port
                <input id="onvif-port" type="number" value="80" min="1" max="65535" required />
              </label>
            </div>
            <div class="row2">
              <label>Username
                <input id="onvif-user" type="text" autocomplete="off" />
              </label>
              <label>Password
                <input id="onvif-pass" type="password" autocomplete="new-password" />
              </label>
            </div>
            <button type="submit" class="btn primary" id="onvif-get-btn">Get Profiles</button>
          </form>
        </div>
      </section>

      <section class="card hidden" id="device-card">
        <div class="card-head">
          <h2>Device</h2>
          <span class="chip hidden" id="onvif-chip"></span>
        </div>
        <div class="card-body">
          <div id="device-body"></div>
          <div id="device-onvif" class="hidden"></div>
        </div>
      </section>

      <section class="card hidden" id="profiles-card">
        <div class="card-head">
          <h2>Profiles</h2>
          <span class="chip" id="profiles-count"></span>
        </div>
        <div class="card-body profiles" id="profiles-list"></div>
      </section>

    </div>

    <!-- ========== Right column: player & details ========== -->
    <div class="col col-right">
      <section class="card" id="player-card">
        <div class="card-head">
          <h2>Player</h2>
          <span class="status-chip idle" id="player-status">Idle</span>
        </div>
        <div class="card-body">
          <div class="video-wrap" id="video-wrap" tabindex="0">
            <video id="video" controls muted playsinline></video>
            <div id="video-overlay" class="video-overlay">No stream playing</div>
            <div id="ptz-pad" class="ptz-pad hidden" aria-label="PTZ controls">
              <span></span>
              <button type="button" class="ptz-btn" data-pan="0" data-tilt="1" aria-label="Tilt up" title="Tilt up (hold)">&#9650;</button>
              <span></span>
              <button type="button" class="ptz-btn" data-pan="-1" data-tilt="0" aria-label="Pan left" title="Pan left (hold)">&#9664;</button>
              <button type="button" class="ptz-btn" data-pan="0" data-tilt="-1" aria-label="Tilt down" title="Tilt down (hold)">&#9660;</button>
              <button type="button" class="ptz-btn" data-pan="1" data-tilt="0" aria-label="Pan right" title="Pan right (hold)">&#9654;</button>
            </div>
            <div id="ptz-note" class="ptz-note hidden"></div>
          </div>
          <div class="player-actions">
            <button id="stop-btn" class="btn danger hidden">&#9632; Stop</button>
          </div>
          <div id="details" class="details hidden"></div>
        </div>
      </section>
    </div>
  </main>

  <!-- ========== ONVIF setup screen (modal) ========== -->
  <div id="onvif-modal" class="modal-backdrop hidden">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="onvif-modal-title">
      <div class="modal-head">
        <h2 id="onvif-modal-title">ONVIF Setup</h2>
        <button id="onvif-modal-close" class="btn ghost small" aria-label="Close">&#10005;</button>
      </div>
      <div class="modal-body">
        <div id="modal-onvif-status" class="onvif-status"></div>
        <div id="provision-suggest" class="note info hidden"></div>
        <p class="card-desc">ONVIF is switched on through the camera's own management API
          (using the device credentials from the connection card). A dedicated ONVIF user is
          created on the camera — afterwards, select the <b>Generic ONVIF</b> driver and sign
          in with that user.</p>
        <form id="provision-form" class="form">
          <div class="row2">
            <label>New ONVIF username
              <input id="provision-user" type="text" required autocomplete="off" />
            </label>
            <label>New ONVIF password
              <input id="provision-pass" type="password" required autocomplete="new-password" />
            </label>
          </div>
          <p class="note">Hikvision requires ONVIF passwords of 8+ characters containing letters and digits.</p>
          <button type="submit" class="btn accent" id="provision-btn">Enable ONVIF on device</button>
        </form>
        <div id="provision-result" class="hidden"></div>
      </div>
    </div>
  </div>`;

  const $ = (id) => document.getElementById(id);
  return {
    errorBanner: $('error-banner'),
    onvifForm: $('onvif-form'),
    driverSelect: $('onvif-driver'),
    driverWarn: $('driver-warn'),
    deviceCard: $('device-card'),
    deviceBody: $('device-body'),
    profilesCard: $('profiles-card'),
    profilesCount: $('profiles-count'),
    profilesList: $('profiles-list'),
    onvifChip: $('onvif-chip'),
    deviceOnvif: $('device-onvif'),
    onvifModal: $('onvif-modal'),
    onvifModalClose: $('onvif-modal-close'),
    modalOnvifStatus: $('modal-onvif-status'),
    provisionSuggest: $('provision-suggest'),
    provisionForm: $('provision-form'),
    provisionUser: $('provision-user'),
    provisionPass: $('provision-pass'),
    provisionBtn: $('provision-btn'),
    provisionResult: $('provision-result'),
    playerStatus: $('player-status'),
    video: $('video'),
    videoWrap: $('video-wrap'),
    overlay: $('video-overlay'),
    ptzPad: $('ptz-pad'),
    ptzNote: $('ptz-note'),
    stopBtn: $('stop-btn'),
    details: $('details'),
    $,
  };
}

/** Populated once by main.js at startup; shared by all ui modules. */
export const els = {};

export function initEls(app) {
  Object.assign(els, buildLayout(app));
  return els;
}
