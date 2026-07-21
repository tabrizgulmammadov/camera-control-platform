// Device info card + compact profile rows (expandable encoder details, per-row Play).
import { state } from '../state.js';
import { els, escapeHtml, encoderSummary } from './dom.js';
import { play } from './player.js';

function fullEncoderRows(p) {
  const rows = [];
  const ve = p.videoEncoder || {};
  if (ve.encoding) rows.push(['Video codec', escapeHtml(ve.encoding)]);
  if (ve.resolution) rows.push(['Resolution', `${ve.resolution.width} × ${ve.resolution.height}`]);
  if (ve.frameRate != null) rows.push(['Frame rate', `${ve.frameRate} fps`]);
  if (ve.bitrateKbps != null) rows.push(['Bitrate', `${ve.bitrateKbps} kbps`]);
  if (ve.quality != null) rows.push(['Quality', String(ve.quality)]);
  if (ve.govLength != null) rows.push(['GOV length', String(ve.govLength)]);
  if (ve.profile) rows.push(['Encoder profile', escapeHtml(ve.profile)]);
  const ae = p.audioEncoder;
  if (ae) {
    const audio = [ae.encoding, ae.bitrateKbps != null ? `${ae.bitrateKbps} kbps` : null,
      ae.sampleRateKhz != null ? `${ae.sampleRateKhz} kHz` : null].filter(Boolean).join(' · ');
    rows.push(['Audio', escapeHtml(audio || '—')]);
  }
  rows.push(['Token', `<span class="mono">${escapeHtml(p.token)}</span>`]);
  rows.push(['RTSP URI', `<span class="mono">${escapeHtml(p.rtspUri || '—')}</span>`]);
  return rows;
}

export function clearCameraResults() {
  state.onvifResult = null;
  els.deviceCard.classList.add('hidden');
  els.deviceBody.innerHTML = '';
  els.profilesCard.classList.add('hidden');
  els.profilesList.innerHTML = '';
  els.profilesCount.textContent = '';
}

export function renderCameraResults() {
  const r = state.onvifResult;
  if (!r) {
    clearCameraResults();
    return;
  }

  // Device card
  const d = r.deviceInfo || {};
  els.deviceBody.innerHTML = `
    <table class="kv">
      <tr><th>Manufacturer</th><td>${escapeHtml(d.manufacturer || '—')}</td></tr>
      <tr><th>Model</th><td>${escapeHtml(d.model || '—')}</td></tr>
      <tr><th>Firmware</th><td>${escapeHtml(d.firmwareVersion || '—')}</td></tr>
      <tr><th>Serial</th><td><span class="mono">${escapeHtml(d.serialNumber || '—')}</span></td></tr>
    </table>`;
  els.deviceCard.classList.remove('hidden');

  // Profiles list
  const profiles = r.profiles || [];
  els.profilesCount.textContent = String(profiles.length);
  els.profilesList.innerHTML = profiles
    .map((p, i) => {
      const badgeClass =
        p.streamType === 'MAIN' ? 'badge main' : p.streamType === 'SUB' ? 'badge sub' : 'badge other';
      return `
      <div class="profile-row" data-token="${escapeHtml(p.token)}">
        <div class="profile-head">
          <span class="${badgeClass}">${escapeHtml(p.streamType || 'OTHER')}</span>
          <span class="profile-name" title="${escapeHtml(p.token)}">${escapeHtml(p.name || p.token)}</span>
          <span class="live-tag">&#9679; PLAYING</span>
          <button class="btn primary small profile-play" data-index="${i}" ${p.rtspUri ? '' : 'disabled'} title="${p.rtspUri ? 'Play this profile' : 'No RTSP URI reported'}">&#9654; Play</button>
        </div>
        <div class="profile-enc">${encoderSummary(p.videoEncoder) || 'No encoder info'}</div>
        <button class="profile-toggle" data-index="${i}" aria-expanded="false">Details &#9662;</button>
        <div class="profile-full hidden">
          <table class="kv">
            ${fullEncoderRows(p).map(([k, v]) => `<tr><th>${k}</th><td>${v}</td></tr>`).join('')}
          </table>
        </div>
      </div>`;
    })
    .join('');
  els.profilesCard.classList.remove('hidden');

  els.profilesList.querySelectorAll('.profile-play').forEach((btn) => {
    btn.addEventListener('click', () => {
      const profile = state.onvifResult.profiles[Number(btn.dataset.index)];
      play({
        rtspUrl: profile.rtspUri,
        username: state.onvifCreds?.username || undefined,
        password: state.onvifCreds?.password || undefined,
        profile,
      });
    });
  });
  els.profilesList.querySelectorAll('.profile-toggle').forEach((btn) => {
    btn.addEventListener('click', () => {
      const full = btn.nextElementSibling;
      const open = full.classList.toggle('hidden');
      btn.setAttribute('aria-expanded', String(!open));
      btn.innerHTML = open ? 'Details &#9662;' : 'Details &#9652;';
    });
  });

  highlightPlayingProfile(state.active?.profile?.token || null);
}

export function highlightPlayingProfile(token) {
  document.querySelectorAll('.profile-row').forEach((row) => {
    row.classList.toggle('playing', token != null && row.dataset.token === token);
  });
}
