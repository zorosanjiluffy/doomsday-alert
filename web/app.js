// Doomsday Alert web app (for iPhone home screen, and any browser).
const cfg = self.DOOM_CONFIG;
const $ = (id) => document.getElementById(id);
const FIRST_SHOW = new Date(cfg.firstShow).getTime();
const LS = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch {} },
};

const ua = navigator.userAgent;
// ?ios and ?standalone are test flags for checking the iPhone flow in a desktop browser.
const q = new URLSearchParams(location.search);
const isIOS = q.has('ios') || /iPhone|iPad|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isAndroid = /Android/.test(ua);
const standalone = q.has('standalone') || window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
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
  $('testMe').hidden = !(on && LS.get('pushToken'));
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
    LS.set('pushToken', token);
  } catch (e) {
    $('err').textContent = e.message || String(e);
    const se = document.getElementById('setupErr');
    if (se) se.textContent = e.message || String(e);
  }
  renderAlerts();
  if (typeof renderSetup === 'function') renderSetup();
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

// ---------- required iPhone setup ----------
// iPhones only get alerts from a Home Screen web app with notifications allowed, and Focus modes
// silence web apps unless they're allowed. The page stays on this screen until every step is done.
const SHARE_SVG = '<svg class="share" viewBox="0 0 16 20" fill="none" stroke="#5DCAA5" stroke-width="1.6" aria-label="Share icon"><path d="M8 13V1M4 5l4-4 4 4M3 8H1.5v10.5h13V8H13"/></svg>';

function alertsAreOn() {
  return LS.get('alertsOn') === '1' && 'Notification' in window && Notification.permission === 'granted' && !!LS.get('pushToken');
}

function setupSteps() {
  return [
    {
      key: 'home', title: 'Add to Home Screen', done: standalone,
      body: `iPhone only sends alerts to apps on your Home Screen.<ol>
        <li>In Safari, tap <b>Share</b> ${SHARE_SVG} at the bottom</li>
        <li>Tap <b>Add to Home Screen</b>, then <b>Add</b></li>
        <li>Close Safari and open <b>Doomsday</b> from your Home Screen</li></ol>`,
      note: 'This step finishes by itself once you open it from the Home Screen.',
    },
    {
      key: 'alerts', title: 'Turn on alerts', done: alertsAreOn(),
      body: 'Tap the button, then tap <b>Allow</b> when iPhone asks about notifications.',
      button: ['Turn on alerts', 'amber', () => enableAlerts()],
    },
    {
      key: 'sound', title: 'Sound and banners on', done: LS.get('setupSound') === '1',
      body: `Open the <b>Settings</b> app → <b>Notifications</b> → <b>Doomsday</b>, and set:<ol>
        <li><b>Allow Notifications</b>: ON</li>
        <li><b>Lock Screen</b>, <b>Notification Centre</b>, <b>Banners</b>: all ticked</li>
        <li><b>Banner Style</b>: <b>Persistent</b> (stays until you tap it)</li>
        <li><b>Sounds</b>: ON</li>
        <li><b>Delivery</b>: <b>Immediate</b>, not Scheduled Summary</li></ol>`,
      button: ['I’ve set all of these', 'green', () => { LS.set('setupSound', '1'); renderSetup(); }],
    },
    {
      key: 'focus', title: 'Let it through Focus', done: LS.get('setupFocus') === '1',
      body: `Focus modes (Do Not Disturb, Sleep…) silence web apps. For each Focus you use:<ol>
        <li><b>Settings</b> → <b>Focus</b> → pick the Focus</li>
        <li><b>Apps</b> → <b>Allow Notifications From</b> → add <b>Doomsday</b></li></ol>`,
      note: 'Don’t use Focus? Then you can just confirm.',
      button: ['Done, Doomsday is allowed', 'green', () => { LS.set('setupFocus', '1'); renderSetup(); }],
    },
    {
      key: 'test', title: 'Test it', done: LS.get('setupTest') === '1',
      body: 'Tap below, then <b>lock your iPhone</b>. In a few seconds a test alert should appear with a sound.',
      button: ['Send me a test alert', 'amber', () => sendTestToMe(true)],
      extra: LS.get('testSentAt') ? ['I got it, with sound', 'green', () => { LS.set('setupTest', '1'); renderSetup(); }] : null,
      note: LS.get('testSentAt') ? 'Nothing came? Check the Sound and Focus steps above, then send another.' : '',
    },
  ];
}

let setupReview = false;
function renderSetup() {
  const box = $('setup');
  if (!isIOS || !pushConfigured) { box.hidden = true; return; }
  const steps = setupSteps();
  const current = steps.findIndex((s) => !s.done);
  if (current < 0 && !setupReview) { box.hidden = true; renderAlerts(); return; }
  box.hidden = false;
  const done = steps.filter((s) => s.done).length;
  box.innerHTML = `<div class="wrap">
    <div class="kicker">${setupReview ? `Setup guide · ${done} of ${steps.length} done` : `Step ${current + 1} of ${steps.length} · ${done} done`}</div>
    <h2>Arm the Doomsday alarm</h2>
    <p class="lead">${setupReview ? 'Everything your iPhone needs for alerts. Check any step again, or redo it if you changed a setting.' : 'These are required. Without them your iPhone can\'t alert you when tickets open, so the app stays on this screen until they\'re done.'}</p>
    ${steps.map((s, i) => `
      <div class="step ${s.done ? 'done' : i === current ? 'active' : ''}">
        <div class="head"><span class="num">${s.done ? '✓' : i + 1}</span>${s.title}</div>
        ${(setupReview || i === current) ? `<div class="body">${s.body}</div>
          ${s.button && !(s.key === 'home') ? `<button class="btn ${s.button[1]}" data-act="main" data-i="${i}" type="button">${s.button[0]}</button>` : ''}
          ${s.extra ? `<button class="btn ${s.extra[1]}" data-act="extra" data-i="${i}" type="button">${s.extra[0]}</button>` : ''}
          ${s.note ? `<div class="note">${s.note}</div>` : ''}
          ${i === current || (setupReview && s.key === 'test') ? '<div class="err" id="setupErr"></div>' : ''}` : ''}
      </div>`).join('')}
    ${setupReview ? '<button class="btn green" data-act="close" type="button">Done</button>' : ''}
  </div>`;
  box.querySelectorAll('[data-act="main"]').forEach((b) => b.addEventListener('click', steps[+b.dataset.i].button[2]));
  box.querySelectorAll('[data-act="extra"]').forEach((b) => b.addEventListener('click', steps[+b.dataset.i].extra[2]));
  box.querySelector('[data-act="close"]')?.addEventListener('click', () => { setupReview = false; renderSetup(); });
}

async function sendTestToMe(fromSetup) {
  const errEl = fromSetup ? $('setupErr') : $('err');
  if (errEl) errEl.textContent = '';
  const token = LS.get('pushToken');
  if (!token) { if (errEl) errEl.textContent = 'Turn on alerts first.'; return; }
  try {
    const r = await fetch(`${cfg.watcherUrl}/test-me`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.ok) throw new Error(j.error || `error ${r.status}`);
    LS.set('testSentAt', String(Date.now()));
    if (fromSetup) renderSetup();
    else if (errEl) errEl.textContent = 'Test sent. Lock your phone and wait a few seconds.';
  } catch (e) {
    if (errEl) errEl.textContent = `Couldn’t send the test: ${e.message}`;
  }
}
$('testMe').addEventListener('click', () => sendTestToMe(false));
renderSetup();
document.addEventListener('visibilitychange', () => { if (!document.hidden) renderSetup(); });

// ---------- shows list (every show, with today's new ones highlighted) ----------
const showsState = { data: null, lang: 'Tamil', date: null, onlyNew: false };
const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const prettyDay = (iso) => new Date(iso + 'T00:00:00+05:30').toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'Asia/Kolkata' });
const seatClass = (s) => (/sold|almost/i.test(s) ? 'red' : /fast/i.test(s) ? 'amber' : 'ok');

async function loadShows() {
  if (!cfg.watcherUrl) return;
  try {
    const r = await fetch(`${cfg.watcherUrl}/shows`, { cache: 'no-store' });
    showsState.data = await r.json();
    renderShows();
  } catch {
    if (!showsState.data) $('shows').querySelector('.shows-body').innerHTML = '<p class="muted">Couldn’t load shows. Retrying…</p>';
  }
}

function renderShows() {
  const d = showsState.data;
  if (!d) return;
  const langs = [...new Set(d.shows.map((s) => s.lang))].sort((a, b) => (b === 'Tamil') - (a === 'Tamil'));
  if (!langs.includes(showsState.lang) && langs.length) showsState.lang = langs[0];
  const sel = $('showLang');
  sel.innerHTML = langs.map((l) => `<option value="${esc(l)}"${l === showsState.lang ? ' selected' : ''}>${esc(l)} (${d.shows.filter((s) => s.lang === l).length})</option>`).join('');

  const newShows = d.shows.filter((s) => s.isNew);
  const banner = $('newBanner');
  banner.hidden = newShows.length === 0;
  if (newShows.length) {
    const split = Object.entries(newShows.reduce((m, s) => ((m[s.lang] = (m[s.lang] || 0) + 1), m), {}))
      .sort((a, b) => b[1] - a[1]).map(([l, n]) => `${n} ${l}`).join(' · ');
    banner.innerHTML = `<span class="ico">🆕</span><span><b>${newShows.length} new show${newShows.length > 1 ? 's' : ''} released today</b><br>
      <small>${esc(split)} · ${showsState.onlyNew ? 'showing only new · tap to show all' : 'tap to see them'}</small></span>`;
  }

  const forLang = d.shows.filter((s) => s.lang === showsState.lang && (!showsState.onlyNew || s.isNew));
  const dates = [...new Set(forLang.map((s) => s.date))].sort();
  const day = dates.includes(showsState.date) ? showsState.date : dates[0];
  const body = $('shows').querySelector('.shows-body');
  if (!dates.length) {
    body.innerHTML = `<p class="muted">${showsState.onlyNew ? `No new ${esc(showsState.lang)} shows today.` : `No ${esc(showsState.lang)} shows listed yet. You’ll get an alert the moment they open.`}</p>`;
    return;
  }
  const chips = dates.map((dt) => `<button type="button" class="chip${dt === day ? ' on' : ''}" data-date="${dt}">${prettyDay(dt)}${forLang.some((s) => s.date === dt && s.isNew) ? ' 🆕' : ''}</button>`).join('');
  const byCinema = {};
  forLang.filter((s) => s.date === day).forEach((s) => (byCinema[s.cinema] ||= []).push(s));
  const cards = Object.entries(byCinema)
    .sort((a, b) => b[1].some((s) => s.isNew) - a[1].some((s) => s.isNew))
    .map(([cinema, list]) => {
      const isNew = list.some((s) => s.isNew);
      const screens = [...new Set(list.map((s) => s.screen).filter(Boolean))].join(' · ');
      const added = list.filter((s) => s.isNew && s.addedAt).map((s) => `${esc(s.time)} (added ${esc(s.addedAt)})`).join(', ');
      const times = list.map((s) => `<a class="time ${seatClass(s.status)}${s.isNew ? ' new' : ''}" href="${esc(s.url)}" target="_blank" rel="noopener">${esc(s.time)}${s.isNew ? ' 🆕' : ''}</a>`).join('');
      return `<div class="cinema${isNew ? ' new' : ''}"><div class="cname"><span>${esc(cinema)}</span>${isNew ? '<em>NEW</em>' : ''}</div>
        ${screens ? `<div class="screens">${esc(screens)}</div>` : ''}
        ${added ? `<div class="added">Released today: ${added}</div>` : ''}<div class="times">${times}</div></div>`;
    }).join('');
  body.innerHTML = `<div class="chips">${chips}</div>${cards}
    <div class="legend"><i class="ok"></i>Available <i class="amber"></i>Filling fast <i class="red"></i>Almost full</div>
    <p class="muted small">Tap a time to book on District.</p>
    ${d.watchingSince ? `<p class="muted small">Checked every ${d.pollSeconds || 30}s · NEW = released today since watching began (${esc(d.watchingSince)}).</p>` : ''}`;
  body.querySelectorAll('.chip').forEach((b) => b.addEventListener('click', () => { showsState.date = b.dataset.date; renderShows(); }));
}

$('showLang').addEventListener('change', (e) => { showsState.lang = e.target.value; showsState.date = null; renderShows(); });
$('newBanner').addEventListener('click', () => {
  showsState.onlyNew = !showsState.onlyNew;
  const d = showsState.data;
  if (showsState.onlyNew && d && !d.shows.some((s) => s.isNew && s.lang === showsState.lang)) {
    const counts = d.shows.filter((s) => s.isNew).reduce((m, s) => ((m[s.lang] = (m[s.lang] || 0) + 1), m), {});
    const top = Object.entries(counts).sort((a, b) => b[1] - a[1])[0];
    if (top) showsState.lang = top[0];
  }
  showsState.date = null;
  renderShows();
});
loadShows();
setInterval(loadShows, 90000);

// ---------- setup guide button ----------
$('guideBtn').hidden = !(isIOS && pushConfigured);
$('guideBtn').addEventListener('click', () => { setupReview = true; renderSetup(); });
