// Doomsday ticket watcher for District (district.in).
//
// Every ~60s it reads the movie's city page for every language format and every
// open date, compares against what it has already seen, and pushes:
//   ALARM  (phone vibrates until opened) when a new theatre gets shows for a language,
//          a new date opens, or a new language is listed
//   INFO   (normal notification) when extra shows are added at a theatre already listed
//
// Push channels (any combination):
//   - Firebase Cloud Messaging topic  -> the Doomsday Android app
//       env FIREBASE_SERVICE_ACCOUNT = service-account JSON (or path to the file)
//   - ntfy topic (config.ntfyTopic)    -> the ntfy phone app
//
//   node watcher.js          run forever
//   node watcher.js --once   one check and exit
//   node watcher.js --test   send a test ALARM through every channel and exit
//
// If PORT is set (Railway sets it), it also serves:
//   GET  /status                 JSON the app shows on its home screen
//   POST /test?key=ADMIN_KEY     sends a test alarm to every phone

const fs = require('fs');
const path = require('path');
const http = require('http');

const DIR = __dirname;
const STATE_DIR = process.env.STATE_DIR || DIR;
const CONFIG_PATH = path.join(DIR, 'config.json');
const STATE_PATH = path.join(STATE_DIR, 'state.json');
const LOG_PATH = path.join(STATE_DIR, 'watcher.log');

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36';
const REQUEST_GAP_MS = 1200;
const FAILURES_BEFORE_ALERT = 5;
const NTFY_MAX_CHARS = 3800;
const FCM_MAX_BODY = 900;

const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
config.ntfyTopic = process.env.NTFY_TOPIC ?? config.ntfyTopic;
config.fcmTopic = process.env.FCM_TOPIC || config.fcmTopic;
const cityLabel = config.city.charAt(0).toUpperCase() + config.city.slice(1);
const baseUrl = `https://www.district.in/movies/${config.movieSlug}-movie-tickets-in-${config.city}-${config.movieId}`;

// ---------- helpers ----------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function log(msg) {
  const line = `[${new Date().toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}] ${msg}`;
  console.log(line);
  try {
    fs.appendFileSync(LOG_PATH, line + '\n');
  } catch {}
}

function loadState() {
  try {
    const s = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
    s.cinemaLangs ??= [];
    s.seen ??= {};
    s.dates ??= [];
    s.formats ??= [];
    return s;
  } catch {
    return { initialized: false, seen: {}, dates: [], formats: [], cinemaLangs: [] };
  }
}

function saveState(state) {
  fs.mkdirSync(STATE_DIR, { recursive: true });
  const tmp = STATE_PATH + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
  fs.renameSync(tmp, STATE_PATH);
}

function pageUrl(formatId, date) {
  const q = new URLSearchParams();
  if (formatId) q.set('frmtid', formatId.toLowerCase());
  if (date) q.set('fromdate', date);
  const qs = q.toString();
  return qs ? `${baseUrl}?${qs}` : baseUrl;
}

function prettyDate(iso) {
  const d = new Date(iso + 'T00:00:00+05:30');
  return d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'Asia/Kolkata' });
}

const shortCinema = (name) => name.replace(new RegExp(`,\\s*${config.city}$`, 'i'), '').replace(/\s*\(.*?\)\s*/g, ' ').trim();

const matchesAny = (text, keywords) =>
  !keywords || keywords.length === 0 || keywords.some((k) => (text || '').toLowerCase().includes(k.toLowerCase()));

// ---------- District scraping ----------

async function fetchNextData(url) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, Accept: 'text/html', 'Accept-Language': 'en-IN,en;q=0.9' },
    signal: AbortSignal.timeout(30000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  const html = await res.text();
  const m = html.match(/<script id="__NEXT_DATA__" type="application\/json"[^>]*>([\s\S]*?)<\/script>/);
  if (!m) throw new Error(`No __NEXT_DATA__ in page ${url}`);
  return JSON.parse(m[1]);
}

function parsePage(nextData) {
  const ss = nextData?.props?.pageProps?.data?.serverState;
  if (!ss) throw new Error('Unexpected page shape: serverState missing');

  const formats = [];
  for (const content of Object.values(ss.movieContents || {})) {
    for (const g of content.languageFormatGroups || []) {
      formats.push({ id: g.fmtGrpId || g.format, language: g.language || g.lang });
    }
  }

  const dates = new Set();
  const sessions = [];
  for (const ms of Object.values(ss.movieSessions || {})) {
    for (const d of ms?.data?.sessionDates || []) dates.add(d);
    for (const cinema of ms?.arrangedSessions || []) {
      const cinemaName = cinema.entityName || cinema.data?.name || 'Unknown cinema';
      for (const s of cinema.sessions || []) {
        sessions.push({
          key: `${s.cid}|${s.sid}`,
          cinemaId: String(s.cid ?? cinema.entityCode),
          cinema: cinemaName,
          date: s.showDate,
          time: s.showTimeText,
          timeISO: s.showTimeISO,
          lang: s.lang,
          format: s.scrnFmt,
          screen: s.premiumLabel || s.audi || '',
          status: s.seatStatus || '',
          formatId: s.fid,
        });
      }
    }
  }
  return { formats, dates: [...dates], sessions };
}

async function collectAll() {
  const all = new Map();
  const dates = new Set();
  const formats = new Map();
  const add = (parsed) => {
    parsed.formats.forEach((f) => formats.set(f.id, f));
    parsed.dates.forEach((d) => dates.add(d));
    parsed.sessions.forEach((s) => all.set(s.key, s));
  };

  add(parsePage(await fetchNextData(pageUrl())));

  for (const f of formats.values()) {
    if (config.languages?.length && !matchesAny(f.language, config.languages)) continue;
    await sleep(REQUEST_GAP_MS);
    const first = parsePage(await fetchNextData(pageUrl(f.id)));
    add(first);
    const fDates = first.dates.length ? first.dates : [...dates];
    for (const d of fDates) {
      await sleep(REQUEST_GAP_MS);
      add(parsePage(await fetchNextData(pageUrl(f.id, d))));
    }
  }

  const sessions = [...all.values()].filter(
    (s) =>
      matchesAny(s.cinema, config.theatreKeywords) &&
      matchesAny(`${s.format} ${s.screen}`, config.formatKeywords) &&
      (!config.languages?.length || matchesAny(s.lang, config.languages)),
  );
  return { sessions, dates: [...dates].sort(), formats: [...formats.values()] };
}

// ---------- push channels ----------

let fcm = null;
function getFcm() {
  if (fcm !== null) return fcm;
  const localKey = path.join(DIR, 'firebase-service-account.json');
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT || (fs.existsSync(localKey) ? localKey : '');
  if (!raw) return (fcm = false);
  try {
    const admin = require('firebase-admin');
    // Accepts the service-account JSON itself, a path to the file, or the JSON base64-encoded.
    const v = raw.trim();
    const creds = v.startsWith('{')
      ? JSON.parse(v)
      : fs.existsSync(v)
        ? JSON.parse(fs.readFileSync(v, 'utf8'))
        : JSON.parse(Buffer.from(v, 'base64').toString('utf8'));
    admin.initializeApp({ credential: admin.credential.cert(creds) });
    fcm = admin.messaging();
    log(`FCM ready (project ${creds.project_id}, topic "${config.fcmTopic}")`);
  } catch (e) {
    log(`!! FCM disabled: ${e.message}`);
    fcm = false;
  }
  return fcm;
}

async function sendFcm({ id, kind, title, message, click }) {
  const m = getFcm();
  if (!m) return;
  try {
    await m.send({
      topic: config.fcmTopic,
      data: {
        kind, // "alarm" | "info"
        title,
        body: message.length > FCM_MAX_BODY ? message.slice(0, FCM_MAX_BODY) + '\n…and more' : message,
        url: click || baseUrl,
        id,
        sentAt: new Date().toISOString(),
      },
      android: { priority: 'high', ttl: kind === 'alarm' ? 3 * 3600 * 1000 : 6 * 3600 * 1000 },
      // Web/iPhone home-screen app: the browser shows this notification itself.
      webpush: {
        headers: { Urgency: 'high', TTL: String(kind === 'alarm' ? 3 * 3600 : 6 * 3600) },
        notification: {
          title,
          body: message.length > 300 ? message.slice(0, 300) + '…' : message,
          icon: `${config.webAppUrl}icon-192.png`,
          badge: `${config.webAppUrl}icon-192.png`,
          tag: kind === 'alarm' ? 'doomsday-alarm' : 'doomsday-info',
          renotify: true,
          requireInteraction: kind === 'alarm',
          vibrate: [900, 350, 900, 350, 1400],
        },
        fcmOptions: { link: click || baseUrl },
      },
    });
    log(`FCM ${kind}: ${title}`);
  } catch (e) {
    log(`!! FCM send failed: ${e.message}`);
  }
}

async function sendNtfy({ kind, title, message, click, tags = [] }) {
  if (!config.ntfyTopic) return;
  const body = {
    topic: config.ntfyTopic,
    title,
    message: message.length > NTFY_MAX_CHARS ? message.slice(0, NTFY_MAX_CHARS) + '\n…and more' : message,
    priority: kind === 'alarm' ? 5 : kind === 'info' ? 4 : 3,
    tags,
  };
  if (click) body.click = click;
  try {
    const res = await fetch(config.ntfyServer || 'https://ntfy.sh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
    log(`ntfy ${kind}: ${title}`);
  } catch (e) {
    log(`!! ntfy failed: ${e.message}`);
  }
}

// kind: "alarm" (vibrate until opened), "info" (normal), "status" (watcher health; ntfy only)
async function notify(n) {
  // One id per alert, shared by the push and /status, so the app can drop duplicates
  // when the same alert arrives both ways.
  n.id = `${Date.now()}`;
  await Promise.all([n.kind === 'status' ? null : sendFcm(n), sendNtfy(n)]);
  if (n.kind !== 'status') {
    status.lastAlert = { id: n.id, kind: n.kind, title: n.title, body: n.message, url: n.click, at: new Date().toISOString() };
  }
}

// ---------- describing changes ----------

function describeShows(shows) {
  const byDate = new Map();
  for (const s of [...shows].sort((a, b) => (a.timeISO || '').localeCompare(b.timeISO || ''))) {
    if (!byDate.has(s.date)) byDate.set(s.date, new Map());
    const byCinema = byDate.get(s.date);
    const k = `${s.cinema}|${s.lang}`;
    if (!byCinema.has(k)) byCinema.set(k, []);
    byCinema.get(k).push(s);
  }
  const lines = [];
  for (const [date, byCinema] of byDate) {
    lines.push(`📅 ${prettyDate(date)}`);
    for (const list of byCinema.values()) {
      const s0 = list[0];
      const times = list.map((s) => s.time).join(', ');
      lines.push(`• ${shortCinema(s0.cinema)} · ${s0.lang}: ${times}`);
    }
  }
  return lines.join('\n');
}

// ---------- status (for the app's home screen) ----------

const status = {
  movie: 'Avengers: Doomsday',
  city: cityLabel,
  watching: baseUrl,
  lastCheck: null,
  lastError: null,
  shows: 0,
  dates: [],
  languages: [],
  theatresByLanguage: {},
  lastAlert: null,
};

function refreshStatus(sessions, dates, formats) {
  status.lastCheck = new Date().toISOString();
  status.lastError = null;
  status.shows = sessions.length;
  status.dates = dates;
  status.languages = formats.map((f) => f.language);
  const byLang = {};
  for (const s of sessions) (byLang[s.lang] ??= new Set()).add(shortCinema(s.cinema));
  status.theatresByLanguage = Object.fromEntries(Object.entries(byLang).map(([k, v]) => [k, v.size]));
}

// ---------- main check ----------

let consecutiveFailures = 0;
let failureAlertSent = false;

async function check() {
  const state = loadState();
  const { sessions, dates, formats } = await collectAll();

  const newDates = dates.filter((d) => !state.dates.includes(d));
  const newFormats = formats.filter((f) => !state.formats.some((x) => x.id === f.id));
  const newShows = sessions.filter((s) => !state.seen[s.key]);
  const knownCL = new Set(state.cinemaLangs);
  if (state.initialized && knownCL.size === 0) {
    // Migrating an older state file: theatres whose shows we've already seen aren't new.
    for (const s of sessions) if (state.seen[s.key]) knownCL.add(`${s.cinemaId}|${s.lang}`);
  }
  const newCinemaShows = newShows.filter((s) => !knownCL.has(`${s.cinemaId}|${s.lang}`));
  const extraShows = newShows.filter((s) => knownCL.has(`${s.cinemaId}|${s.lang}`));
  const now = new Date().toISOString();

  if (!state.initialized) {
    await notify({
      kind: 'status',
      title: `Doomsday watcher is live for ${cityLabel}`,
      message: `Tracking ${sessions.length} existing shows across ${dates.length} dates (${dates.map(prettyDate).join(' · ')}).`,
      click: baseUrl,
      tags: ['eyes'],
    });
  } else if (newCinemaShows.length || newDates.length || newFormats.length) {
    // ALARM: something genuinely new opened.
    const parts = [];
    const newTheatres = new Map();
    for (const s of newCinemaShows) newTheatres.set(`${s.cinemaId}|${s.lang}`, s);
    let title;
    if (newTheatres.size) {
      const langs = [...new Set([...newTheatres.values()].map((s) => s.lang))].join(' + ');
      const names = [...newTheatres.values()].map((s) => shortCinema(s.cinema));
      title =
        newTheatres.size === 1
          ? `🚨 ${names[0]} just opened ${langs} tickets!`
          : `🚨 ${newTheatres.size} more theatres opened ${langs} tickets!`;
    } else if (newDates.length) {
      title = `🚨 Booking opened for ${newDates.map(prettyDate).join(', ')}!`;
    } else {
      title = `🚨 ${newFormats.map((f) => f.language).join(', ')} shows are now listed!`;
    }
    if (newFormats.length) parts.push(`New language: ${newFormats.map((f) => f.language).join(', ')}`);
    if (newDates.length) parts.push(`New dates: ${newDates.map(prettyDate).join(' · ')}`);
    if (newShows.length) parts.push(describeShows(newShows));
    const first = newCinemaShows[0] || newShows[0];
    await notify({
      kind: 'alarm',
      title,
      message: parts.join('\n'),
      click: first ? pageUrl(first.formatId, first.date) : pageUrl(null, newDates[0]),
      tags: ['rotating_light', 'ticket'],
    });
  } else if (extraShows.length) {
    const cinemas = new Set(extraShows.map((s) => s.cinema));
    const first = extraShows[0];
    await notify({
      kind: 'info',
      title: `🎟️ ${extraShows.length} more show${extraShows.length > 1 ? 's' : ''} added (${cinemas.size} theatre${cinemas.size > 1 ? 's' : ''})`,
      message: describeShows(extraShows),
      click: pageUrl(first.formatId, first.date),
      tags: ['ticket'],
    });
  }

  for (const s of newShows) state.seen[s.key] = now;
  for (const s of sessions) knownCL.add(`${s.cinemaId}|${s.lang}`);
  state.cinemaLangs = [...knownCL];
  state.dates = [...new Set([...state.dates, ...dates])].sort();
  state.formats = [...new Map([...state.formats, ...formats].map((f) => [f.id, f])).values()];
  state.initialized = true;
  state.lastCheck = now;
  saveState(state);

  refreshStatus(sessions, dates, formats);
  log(
    `Checked: ${sessions.length} shows, ${dates.length} dates, ${formats.length} formats | new theatre-lang: ${new Set(newCinemaShows.map((s) => s.cinemaId + s.lang)).size}, extra shows: ${extraShows.length}, new dates: ${newDates.length}`,
  );
}

async function runOnce() {
  try {
    await check();
    if (failureAlertSent) {
      await notify({ kind: 'status', title: 'Doomsday watcher recovered', message: 'Checks are working again.', tags: ['white_check_mark'] });
    }
    consecutiveFailures = 0;
    failureAlertSent = false;
  } catch (e) {
    consecutiveFailures++;
    status.lastError = e.message;
    log(`!! Check failed (${consecutiveFailures} in a row): ${e.message}`);
    if (consecutiveFailures >= FAILURES_BEFORE_ALERT && !failureAlertSent) {
      failureAlertSent = true;
      await notify({
        kind: 'status',
        title: '⚠️ Doomsday watcher is having trouble',
        message: `Last ${consecutiveFailures} checks failed: ${e.message}`,
        click: baseUrl,
        tags: ['warning'],
      });
    }
  }
}

function sendTestAlarm() {
  return notify({
    kind: 'alarm',
    title: '🚨 TEST: Doomsday tickets just dropped!',
    message: `This is a test alarm from the watcher.\nIf your phone is buzzing, you're all set.\nTap to stop.`,
    click: baseUrl,
    tags: ['tada'],
  });
}

function startServer() {
  const port = process.env.PORT;
  if (!port) return;
  http
    .createServer(async (req, res) => {
      const url = new URL(req.url, 'http://x');
      if (url.pathname === '/status') {
        res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
        return res.end(JSON.stringify(status));
      }
      if (url.pathname === '/subscribe-web') {
        // The iPhone/web app posts its FCM token here; we add it to the alerts topic.
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
        if (req.method === 'OPTIONS') {
          res.writeHead(204);
          return res.end();
        }
        if (req.method !== 'POST') {
          res.writeHead(405);
          return res.end();
        }
        let body = '';
        for await (const chunk of req) {
          body += chunk;
          if (body.length > 4096) break;
        }
        try {
          const token = JSON.parse(body).token;
          const m = getFcm();
          if (typeof token !== 'string' || token.length < 20 || token.length > 1024 || !m) throw new Error('bad request');
          const r = await m.subscribeToTopic([token], config.fcmTopic);
          if (r.failureCount) throw new Error(r.errors[0]?.error?.message || 'subscribe failed');
          status.webSubscribers = (status.webSubscribers || 0) + 1;
          log('Web app subscribed to alerts');
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end('{"ok":true}');
        } catch (e) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ ok: false, error: e.message }));
        }
      }
      if (url.pathname === '/test' && req.method === 'POST') {
        if (!process.env.ADMIN_KEY || url.searchParams.get('key') !== process.env.ADMIN_KEY) {
          res.writeHead(403);
          return res.end('forbidden');
        }
        await sendTestAlarm();
        res.writeHead(200);
        return res.end('test alarm sent');
      }
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('Doomsday watcher is running. See /status');
    })
    .listen(port, () => log(`Status server on :${port}`));
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--test')) {
    await sendTestAlarm();
    return;
  }
  log(`Watching ${baseUrl} every ${config.pollSeconds}s | FCM topic "${config.fcmTopic}" | ntfy "${config.ntfyTopic || '-'}"`);
  getFcm();
  startServer();
  await runOnce();
  if (args.includes('--once')) return;
  for (;;) {
    await sleep(config.pollSeconds * 1000 + Math.floor(Math.random() * 10000));
    await runOnce();
  }
}

main();
