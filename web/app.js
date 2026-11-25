// Doomsday Alert web app (for iPhone home screen, and any browser).
const cfg = self.DOOM_CONFIG;
const $ = (id) => document.getElementById(id);
const FIRST_SHOW = new Date(cfg.firstShow).getTime();
const LS = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch {} },
};

const ua = navigator.userAgent;
const isIOS = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isAndroid = /Android/.test(ua);
const standalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
const pushConfigured = Boolean(cfg.firebase.appId && cfg.vapidKey && cfg.watcherUrl);

const state = { speed: 1, alarm: false, pulseKey: null, status: null };

// ---------- static wiring ----------
$('open').href = cfg.districtUrl;
$('alertGo').href = cfg.districtUrl;
$('apkLink').href = cfg.apkUrl;
$('android').hidden = !isAndroid;
$('install').hidden = !(isIOS && !standalone);

DoomSigil.start($('sigil'), () => state);

// ---------- countdown ----------
function tick() {
  const ms = FIRST_SHOW - Date.now();
  const days = ms / 86400000;
  state.speed = 1 + 2 * (1 - Math.min(1, Math.max(0, days / 30)));
  if (ms <= 0) {
    $('cdLabel').textContent = 'Doom has arrived';
    $('countdown').textContent = '00d 00h 00m 00s';
    return;
  }
  const s = Math.floor(ms / 1000), z = (n) => String(n).padStart(2, '0');
  $('countdown').textContent = `${z(Math.floor(s / 86400))}d ${z(Math.floor((s % 86400) / 3600))}h ${z(Math.floor((s % 3600) / 60))}m ${z(s % 60)}s`;
  renderLast();
}
tick();
setInterval(tick, 1000);

// ---------- watcher status ----------
// A hoisted function: tick() runs before this point and needs it.
function ago(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return s < 60 ? `${s}s ago` : s < 3600 ? `${Math.floor(s / 60)} min ago` : s < 86400 ? `${Math.floor(s / 3600)} h ago` : `${Math.floor(s / 86400)} d ago`;
}

async function loadStatus() {
  if (!cfg.watcherUrl) return;
  try {
    const r = await fetch(`${cfg.watcherUrl}/status`, { cache: 'no-store' });
    const s = await r.json();
    state.status = s;
    if (s.lastCheck) state.pulseKey = s.lastCheck;
    if (s.lastAlert) {
      LS.set('lastAlert', JSON.stringify(s.lastAlert));
    }
    renderStatus();
    renderLast();
  } catch {
    $('status').textContent = 'Contacting the watcher…';
  }
}

function renderStatus() {
  const s = state.status;
  if (!s || !s.lastCheck) return;
  const tamil = (s.theatresByLanguage && s.theatresByLanguage.Tamil) || 0;
  let line = `Last check ${ago(Date.now() - new Date(s.lastCheck).getTime())} · ${s.shows} shows · Tamil at ${tamil} theatres`;
  if (s.lastError) line += `\nWatcher is retrying`;
  $('status').textContent = line;
}
setInterval(renderStatus, 5000);
loadStatus();
setInterval(loadStatus, 30000);

function renderLast() {
  let a = null;
  try { a = JSON.parse(LS.get('lastAlert') || 'null'); } catch {}
  if (!a || !a.title) { $('last').hidden = true; state.alarm = false; return; }
  const at = new Date(a.at).getTime();
  const isAlarm = a.kind === 'alarm';
  state.alarm = isAlarm && Date.now() - at < 30 * 60000;
  $('last').hidden = false;
  $('last').classList.toggle('alarm', isAlarm);
  $('lastWhen').textContent = `Last alert · ${ago(Date.now() - at)}`;
  $('lastTitle').textContent = a.title;
  $('lastBody').textContent = a.body || '';
  if (a.url) $('open').href = a.url;
}

// ---------- pill + alerts button ----------
function setPill(ok, text) {
  $('pill').classList.toggle('ok', ok);
  $('pillText').textContent = text;
}

function renderAlerts() {
  const btn = $('alerts');
  const on = LS.get('alertsOn') === '1' && 'Notification' in window && Notification.permission === 'granted';
  if (!pushConfigured) {
    setPill(false, 'Alerts are being set up');
    btn.textContent = 'Alerts coming soon';
    btn.disabled = true;
    return;
  }
  if (isIOS && !standalone) {
    setPill(false, 'Add to Home Screen to get alerts');
    btn.hidden = true;
    return;
  }
  if (!('Notification' in window) || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    setPill(false, 'This browser can’t receive alerts');
    btn.hidden = true;
    return;
  }
  btn.hidden = false;
  btn.disabled = false;
  btn.classList.toggle('on', on);
  btn.textContent = on ? 'Alerts are on ✓  (tap to re-check)' : 'Turn on alerts';
  setPill(on, on ? 'Watching District · Chennai' : 'Alerts are off');
}
renderAlerts();

let messaging = null;
async function firebaseMessaging() {
  if (messaging) return messaging;
  const { initializeApp } = await import('https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js');
  const m = await import('https://www.gstatic.com/firebasejs/11.0.2/firebase-messaging.js');
  const app = initializeApp(cfg.firebase);
  messaging = { api: m, instance: m.getMessaging(app) };
  m.onMessage(messaging.instance, (payload) => showAlert(payload));
  return messaging;
}

async function enableAlerts() {
  $('err').textContent = '';
  const btn = $('alerts');
  btn.disabled = true;
  btn.textContent = 'Turning on…';
  try {
    const perm = await Notification.requestPermission();
    if (perm !== 'granted') throw new Error('Notifications are blocked. Allow them in Settings → Notifications → Doomsday.');
    const reg = await navigator.serviceWorker.register('sw.js', { scope: './' });
    await navigator.serviceWorker.ready;
    const { api, instance } = await firebaseMessaging();
    const token = await api.getToken(instance, { vapidKey: cfg.vapidKey, serviceWorkerRegistration: reg });
    if (!token) throw new Error('Couldn’t get a push token. Try again.');
    const r = await fetch(`${cfg.watcherUrl}/subscribe-web`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.ok) throw new Error(`Couldn’t connect to the watcher (${j.error || r.status}). Try again.`);
    LS.set('alertsOn', '1');
  } catch (e) {
    $('err').textContent = e.message || String(e);
  }
  renderAlerts();
}
$('alerts').addEventListener('click', enableAlerts);

// Keep the foreground listener alive on later visits.
if (pushConfigured && LS.get('alertsOn') === '1' && 'Notification' in window && Notification.permission === 'granted') {
  navigator.serviceWorker.register('sw.js', { scope: './' }).then(() => firebaseMessaging()).catch(() => {});
}

// ---------- in-app alert (push arrived while the app is open) ----------
let buzzTimer = null;
function showAlert(payload) {
  const n = payload.notification || {};
  const link = payload.fcmOptions?.link || payload.data?.url || cfg.districtUrl;
  LS.set('lastAlert', JSON.stringify({ kind: payload.data?.kind || 'alarm', title: n.title, body: n.body, url: link, at: new Date().toISOString() }));
  renderLast();
  $('alertTitle').textContent = n.title || 'Doomsday tickets just dropped';
  $('alertBody').textContent = n.body || '';
  $('alertGo').href = link;
  $('alert').hidden = false;
  $('alert').classList.add('buzz');
  if (navigator.vibrate) {
    navigator.vibrate([900, 350, 900, 350, 1400]);
    buzzTimer = setInterval(() => navigator.vibrate([900, 350, 900, 350, 1400]), 4000);
  }
}
function stopBuzz() {
  $('alert').classList.remove('buzz');
  clearInterval(buzzTimer);
  if (navigator.vibrate) navigator.vibrate(0);
}
$('alert').addEventListener('pointerdown', stopBuzz);
$('alertClose').addEventListener('click', () => { stopBuzz(); $('alert').hidden = true; });
$('alertGo').addEventListener('click', () => { stopBuzz(); $('alert').hidden = true; });
