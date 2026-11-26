// Doomsday ticket watcher for District (district.in).
//
// Every ~60s it reads the movie's city page for every language format and every
// open date, compares against what it has already seen, and pushes:
//   ALARM  (phone vibrates until opened) for anything new: every new show, a new screen,
//          a new theatre, a new date, or a new language
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
//        &firstday=1             ...as a first-day drill (3 repeats + WhatsApp)
//   POST /test-whatsapp?key=ADMIN_KEY   sends one test WhatsApp + Telegram message
//   POST /stop?key=ADMIN_KEY     stops a running first-day repeat
//
// First-day watch (config.firstDay): a new show on release day before beforeHour
// (the 12:01 AM / 6 AM shows), or any show before release day, rings the alarm
// again every repeatEverySeconds, repeats times, and sends WhatsApp messages.
//   env WHATSAPP_TO = "+91XXXXXXXXXX:apikey[,+91...:apikey]"  (CallMeBot keys)
//   env TELEGRAM_BOT_TOKEN = token from @BotFather. Anyone who sends /start to the bot
//       gets the first-day messages (/stop to leave); chats are kept in telegram.json.
//   env DRY_RUN=1   log alerts instead of sending them (local testing)

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
const REQUEST_GAP_MS = 400;
const FAILURES_BEFORE_ALERT = 5;
const NTFY_MAX_CHARS = 3800;
const FCM_MAX_BODY = 900;

const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
config.ntfyTopic = process.env.NTFY_TOPIC ?? config.ntfyTopic;
config.fcmTopic = process.env.FCM_TOPIC || config.fcmTopic;
const DRY_RUN = !!process.env.DRY_RUN;
config.firstDay = { date: '2026-12-17', beforeHour: 8, repeatEverySeconds: 60, repeats: 15, whatsappRepeats: 5, ...(config.firstDay || {}) };
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
          audi: s.audi || s.premiumLabel || '',
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

async function sendFcm({ id, series, kind, title, message, click }) {
  if (DRY_RUN) return log(`[dry] FCM ${kind}: ${title}`);
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
        series: series || '',
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
  if (DRY_RUN) return log(`[dry] ntfy ${kind}: ${title}`);
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

// WhatsApp through CallMeBot (free, personal use). Each recipient activates it once
// from their own WhatsApp and gets an apikey.
function whatsappRecipients() {
  return (process.env.WHATSAPP_TO || '')
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean)
    .map((x) => {
      const i = x.lastIndexOf(':');
      return { phone: x.slice(0, i).replace(/[^+\d]/g, ''), apikey: x.slice(i + 1).trim() };
    })
    .filter((r) => r.phone && r.apikey);
}

async function sendWhatsApp(text) {
  const list = whatsappRecipients();
  if (!list.length) return log('WhatsApp: no WHATSAPP_TO set, skipped');
  for (const r of list) {
    const tail = r.phone.slice(-4);
    if (DRY_RUN) {
      log(`[dry] WhatsApp to ...${tail}: ${text.split('\n')[0]}`);
      continue;
    }
    try {
      const q = new URLSearchParams({ phone: r.phone, text: text.slice(0, 1500), apikey: r.apikey });
      const res = await fetch(`https://api.callmebot.com/whatsapp.php?${q}`, { signal: AbortSignal.timeout(20000) });
      const body = (await res.text()).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
      if (!res.ok || /apikey is invalid|error/i.test(body)) throw new Error(`HTTP ${res.status}: ${body.slice(0, 160)}`);
      log(`WhatsApp sent to ...${tail}`);
    } catch (e) {
      log(`!! WhatsApp to ...${tail} failed: ${e.message}`);
    }
    await sleep(1500);
  }
}

// Telegram: an official bot. People join by sending /start to it.
const TG_PATH = path.join(STATE_DIR, 'telegram.json');
const tgToken = () => (process.env.TELEGRAM_BOT_TOKEN || '').trim();
function tgLoad() {
  try {
    return JSON.parse(fs.readFileSync(TG_PATH, 'utf8'));
  } catch {
    return { offset: 0, chats: {} };
  }
}
function tgSave(t) {
  fs.mkdirSync(STATE_DIR, { recursive: true });
  fs.writeFileSync(TG_PATH, JSON.stringify(t, null, 2));
}
async function tgApi(method, body) {
  const res = await fetch(`https://api.telegram.org/bot${tgToken()}/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  const j = await res.json().catch(() => ({}));
  if (!j.ok) throw new Error(`${method}: ${j.description || 'HTTP ' + res.status}`);
  return j.result;
}

// Picks up /start and /stop sent to the bot since last time.
let tgPolling = false;
async function telegramPoll() {
  if (!tgToken() || DRY_RUN || tgPolling) return;
  tgPolling = true;
  try {
    const t = tgLoad();
    const updates = await tgApi('getUpdates', { offset: t.offset, timeout: 0, allowed_updates: ['message'] });
    for (const u of updates) {
      t.offset = u.update_id + 1;
      const m = u.message;
      if (!m?.chat || typeof m.text !== 'string') continue;
      const id = String(m.chat.id);
      const name = m.chat.first_name || m.chat.title || m.chat.username || id;
      if (/^\/start\b/i.test(m.text)) {
        t.chats[id] = { name, at: new Date().toISOString() };
        log(`Telegram: ${name} joined (${Object.keys(t.chats).length} chats)`);
        await tgApi('sendMessage', { chat_id: id, text: `✅ Hi ${name}! You'll get a message here the moment a first-day (12:01 AM / 6 AM) Avengers: Doomsday show opens in ${cityLabel}.\nSend /stop to stop.` }).catch(() => {});
      } else if (/^\/stop\b/i.test(m.text)) {
        delete t.chats[id];
        log(`Telegram: ${name} left`);
        await tgApi('sendMessage', { chat_id: id, text: 'Stopped. Send /start to join again.' }).catch(() => {});
      }
    }
    tgSave(t);
    status.telegramChats = Object.keys(t.chats).length;
  } catch (e) {
    log(`!! Telegram poll failed: ${e.message}`);
  } finally {
    tgPolling = false;
  }
}

async function sendTelegram(text) {
  if (!tgToken()) return log('Telegram: no TELEGRAM_BOT_TOKEN set, skipped');
  const chats = Object.entries(tgLoad().chats);
  if (DRY_RUN) return log(`[dry] Telegram to ${chats.length} chats: ${text.split('\n')[0]}`);
  if (!chats.length) return log('Telegram: nobody has sent /start to the bot yet');
  for (const [id, c] of chats) {
    try {
      await tgApi('sendMessage', { chat_id: id, text: text.slice(0, 4000), disable_web_page_preview: true });
      log(`Telegram sent to ${c.name}`);
    } catch (e) {
      log(`!! Telegram to ${c.name} failed: ${e.message}`);
    }
  }
}

// WhatsApp and Telegram together.
const sendMessages = (text) => Promise.all([sendWhatsApp(text), sendTelegram(text)]);

// kind: "alarm" (vibrate until opened), "info" (normal), "status" (watcher health; ntfy only)
async function notify(n) {
  // One id per alert, shared by the push and /status, so the app can drop duplicates
  // when the same alert arrives both ways.
  n.id = `${Date.now()}`;
  log(`Alert (${n.kind}): ${n.title}`);
  await Promise.all([n.kind === 'status' ? null : sendFcm(n), sendNtfy(n)]);
  if (n.kind !== 'status') {
    status.lastAlert = { id: n.id, series: n.series || '', kind: n.kind, title: n.title, body: n.message, url: n.click, at: new Date().toISOString() };
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

// ---------- first-day watch (12:01 AM / 6 AM shows) ----------

function showHour(s) {
  const m = /(\d{1,2}):(\d{2})\s*([AP]M)/i.exec(s.time || '');
  if (!m) return null;
  let h = Number(m[1]) % 12;
  if (/p/i.test(m[3])) h += 12;
  return h;
}

// A show we must never miss: release day before beforeHour, or anything before release day.
function isFirstDayShow(s) {
  const fd = config.firstDay;
  if (!fd?.date || !s.date) return false;
  if (s.date < fd.date) return true;
  if (s.date !== fd.date) return false;
  const h = showHour(s);
  return h !== null && h < fd.beforeHour;
}

let repeatTimer = null;
let repeatInfo = null;

function stopRepeats(reason) {
  if (repeatTimer) clearTimeout(repeatTimer);
  repeatTimer = null;
  if (repeatInfo) log(`First-day repeat stopped (${reason})`);
  repeatInfo = null;
  status.firstDayRepeat = null;
}

// Ring again every everySeconds; WhatsApp on the first whatsappRounds rounds.
function startRepeats({ series, title, message, click, repeats, everySeconds, whatsappRounds }) {
  stopRepeats('restarted with newer shows');
  const r = { title, message, click, left: repeats, round: 1, repeats, whatsappRounds };
  repeatInfo = r;
  status.firstDayRepeat = { title, left: repeats, startedAt: new Date().toISOString() };
  const tick = async () => {
    if (repeatInfo !== r) return;
    r.round++;
    r.left--;
    status.firstDayRepeat = { ...status.firstDayRepeat, left: r.left };
    const t = `${r.title} (reminder ${r.round}/${r.repeats + 1})`;
    await notify({ kind: 'alarm', series, title: t, message: r.message, click: r.click, tags: ['rotating_light', 'fire'] });
    if (r.round <= r.whatsappRounds) await sendMessages(`${t}\n\n${r.message}\n\nBook: ${r.click}`);
    if (repeatInfo !== r) return;
    if (r.left > 0) repeatTimer = setTimeout(tick, everySeconds * 1000);
    else stopRepeats('all reminders sent');
  };
  repeatTimer = setTimeout(tick, everySeconds * 1000);
}

async function firstDayAlert(shows, { test = false } = {}) {
  const fd = config.firstDay;
  const early = [...shows].sort((a, b) => (showHour(a) ?? 99) - (showHour(b) ?? 99) || (a.time || '').localeCompare(b.time || ''));
  const s0 = early[0];
  const cinemas = new Set(early.map((s) => s.cinema));
  const title = `${test ? 'TEST: ' : ''}🔥 FIRST DAY ${prettyDate(s0.date)} ${s0.time} show open! ${shortCinema(s0.cinema)}${cinemas.size > 1 ? ` +${cinemas.size - 1} more` : ''}`;
  const message = `${test ? 'This is a drill.\n' : ''}${describeShows(early)}\nBook NOW before it sells out!`;
  const click = pageUrl(s0.formatId, s0.date);
  // Every reminder carries the same series id: a phone that has opened the app stops ringing for it.
  const series = `fd-${Date.now()}`;
  await notify({ kind: 'alarm', series, title, message, click, tags: ['rotating_light', 'fire'] });
  await sendMessages(`${title}\n\n${message}\n\nBook: ${click}`);
  startRepeats({
    series,
    title,
    message,
    click,
    repeats: test ? 3 : fd.repeats,
    everySeconds: test ? 30 : fd.repeatEverySeconds,
    whatsappRounds: test ? 2 : fd.whatsappRepeats,
  });
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
let latestShows = { updated: null, baselineAt: null, shows: [] };
let testMeLog = [];
let failureAlertSent = false;

async function check() {
  const state = loadState();
  const { sessions, dates, formats } = await collectAll();

  const newDates = dates.filter((d) => !state.dates.includes(d));
  const newFormats = formats.filter((f) => !state.formats.some((x) => x.id === f.id));
  const newShows = sessions.filter((s) => !state.seen[s.key]);
  const clKey = (s) => `${s.cinemaId}|${s.lang}`;
  const scrKey = (s) => `${s.cinemaId}|${s.lang}|${s.audi}`;
  const knownCL = new Set(state.cinemaLangs);
  const knownScr = new Set(state.cinemaScreens || []);
  if (state.initialized) {
    // Older state files didn't track these: whatever we'd already seen isn't new.
    for (const s of sessions) {
      if (!state.seen[s.key]) continue;
      if (!state.cinemaLangs.length) knownCL.add(clKey(s));
      if (!state.cinemaScreens) knownScr.add(scrKey(s));
    }
  }
  const newTheatreShows = newShows.filter((s) => !knownCL.has(clKey(s)));
  const newScreenShows = newShows.filter((s) => knownCL.has(clKey(s)) && !knownScr.has(scrKey(s)));
  const now = new Date().toISOString();

  if (!state.initialized) {
    await notify({
      kind: 'status',
      title: `Doomsday watcher is live for ${cityLabel}`,
      message: `Tracking ${sessions.length} existing shows across ${dates.length} dates (${dates.map(prettyDate).join(' · ')}).`,
      click: baseUrl,
      tags: ['eyes'],
    });
  } else if (newShows.some(isFirstDayShow)) {
    // The 12:01 AM / 6 AM first-day shows: alarm, WhatsApp, and keep repeating.
    await firstDayAlert(newShows.filter(isFirstDayShow));
    const rest = newShows.filter((s) => !isFirstDayShow(s));
    if (rest.length) log(`(also ${rest.length} other new shows, listed as NEW in the app)`);
  } else if (newShows.length || newDates.length || newFormats.length) {
    // ALARM for anything new at all: every new show, screen, theatre, date or language.
    const uniq = (arr, key) => [...new Map(arr.map((s) => [key(s), s])).values()];
    const theatres = uniq(newTheatreShows, clKey);
    const screens = uniq(newScreenShows, scrKey);
    const langsOf = (arr) => [...new Set(arr.map((s) => s.lang))].join(' + ');
    let title;
    if (theatres.length) {
      const cinemaCount = new Set(theatres.map((s) => s.cinemaId)).size;
      title = cinemaCount === 1
        ? `🚨 ${shortCinema(theatres[0].cinema)} just opened ${langsOf(theatres)} tickets!`
        : `🚨 ${cinemaCount} more theatres opened ${langsOf(theatres)} tickets!`;
    } else if (screens.length) {
      title = screens.length === 1
        ? `🚨 New ${screens[0].lang} screen at ${shortCinema(screens[0].cinema)}${screens[0].audi ? ` (${screens[0].audi})` : ''}!`
        : `🚨 ${screens.length} new screens opened for ${langsOf(screens)}!`;
    } else if (newDates.length) {
      title = `🚨 Booking opened for ${newDates.map(prettyDate).join(', ')}!`;
    } else if (newFormats.length) {
      title = `🚨 ${newFormats.map((f) => f.language).join(', ')} shows are now listed!`;
    } else {
      const cinemas = new Set(newShows.map((s) => s.cinema));
      title = newShows.length === 1
        ? `🚨 New ${newShows[0].lang} show: ${shortCinema(newShows[0].cinema)} ${newShows[0].time}!`
        : `🚨 ${newShows.length} new ${langsOf(newShows)} shows (${cinemas.size} theatre${cinemas.size > 1 ? 's' : ''})!`;
    }
    const parts = [];
    if (newFormats.length) parts.push(`New language: ${newFormats.map((f) => f.language).join(', ')}`);
    if (newDates.length) parts.push(`New dates: ${newDates.map(prettyDate).join(' · ')}`);
    if (newShows.length) parts.push(describeShows(newShows));
    const first = theatres[0] || screens[0] || newShows[0];
    await notify({
      kind: 'alarm',
      title,
      message: parts.join('\n'),
      click: first ? pageUrl(first.formatId, first.date) : pageUrl(null, newDates[0]),
      tags: ['rotating_light', 'ticket'],
    });
  }

  for (const s of newShows) state.seen[s.key] = now;
  for (const s of sessions) {
    knownCL.add(clKey(s));
    knownScr.add(scrKey(s));
  }
  state.cinemaLangs = [...knownCL];
  state.cinemaScreens = [...knownScr];
  state.dates = [...new Set([...state.dates, ...dates])].sort();
  state.formats = [...new Map([...state.formats, ...formats].map((f) => [f.id, f])).values()];
  state.initialized = true;
  state.lastCheck = now;
  saveState(state);

  const seenTimes = Object.values(state.seen).sort();
  latestShows = {
    updated: now,
    baselineAt: seenTimes[0] || now,
    shows: sessions
      .map((x) => ({
        lang: x.lang,
        date: x.date,
        cinema: shortCinema(x.cinema),
        time: x.time,
        iso: x.timeISO,
        screen: x.screen,
        format: x.format,
        status: x.status,
        url: pageUrl(x.formatId, x.date),
        firstSeen: state.seen[x.key] || now,
      }))
      .sort((a, b) => (a.iso || '').localeCompare(b.iso || '') || a.cinema.localeCompare(b.cinema)),
  };
  refreshStatus(sessions, dates, formats);
  log(
    `Checked: ${sessions.length} shows, ${dates.length} dates, ${formats.length} formats | new shows: ${newShows.length} (new theatres: ${new Set(newTheatreShows.map(clKey)).size}, new screens: ${new Set(newScreenShows.map(scrKey)).size}), new dates: ${newDates.length}`,
  );
}

async function runOnce() {
  telegramPoll();
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
      if (url.pathname === '/shows') {
        const istDay = (iso) => new Date(new Date(iso).getTime() + 330 * 60000).toISOString().slice(0, 10);
        const today = istDay(new Date().toISOString());
        const base = latestShows.baselineAt;
        const istTime = (iso) => new Date(iso).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'Asia/Kolkata' });
        const shows = latestShows.shows.map(({ firstSeen, iso, ...x }) => {
          const isNew = !!base && firstSeen > base && istDay(firstSeen) === today;
          return { ...x, isNew, ...(isNew ? { addedAt: istTime(firstSeen) } : {}) };
        });
        res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
        const since = base ? new Date(base).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'Asia/Kolkata' }) : null;
        return res.end(JSON.stringify({ updated: latestShows.updated, today, watchingSince: since, pollSeconds: config.pollSeconds, newToday: shows.filter((x) => x.isNew).length, shows }));
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
      if (url.pathname === '/test-me') {
        // A friend's web app asks for a test alert to its own token only (rate-limited).
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
        if (req.method === 'OPTIONS') {
          res.writeHead(204);
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
          const now = Date.now();
          testMeLog = testMeLog.filter((e) => now - e.at < 60_000);
          if (testMeLog.length >= 30 || testMeLog.some((e) => e.token === token)) throw new Error('Wait a minute before testing again');
          testMeLog.push({ token, at: now });
          await m.send({
            token,
            webpush: {
              headers: { Urgency: 'high', TTL: '600' },
              notification: {
                title: '🚨 TEST: Doomsday tickets just dropped!',
                body: 'This is your test alert. If you saw and heard this, your iPhone is ready.',
                icon: `${config.webAppUrl}icon-192.png`,
                tag: 'doomsday-test',
                requireInteraction: true,
              },
              fcmOptions: { link: config.webAppUrl },
            },
          });
          log('Sent a personal test alert to one web app');
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end('{"ok":true}');
        } catch (e) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ ok: false, error: e.message }));
        }
      }
      if (['/test', '/test-whatsapp', '/stop'].includes(url.pathname) && req.method === 'POST') {
        if (!process.env.ADMIN_KEY || url.searchParams.get('key') !== process.env.ADMIN_KEY) {
          res.writeHead(403);
          return res.end('forbidden');
        }
        if (url.pathname === '/stop') {
          const was = !!repeatInfo;
          stopRepeats('stopped by admin');
          res.writeHead(200);
          return res.end(was ? 'repeats stopped' : 'nothing was repeating');
        }
        if (url.pathname === '/test-whatsapp') {
          await telegramPoll();
          const n = whatsappRecipients().length;
          const tg = Object.keys(tgLoad().chats).length;
          await sendMessages('🚨 TEST from the Doomsday watcher: message alerts are working.');
          res.writeHead(200);
          return res.end(`test message sent: WhatsApp ${n} number(s), Telegram ${tg} chat(s); see logs`);
        }
        if (url.searchParams.get('firstday')) {
          await firstDayAlert([{ cinema: 'Kamala Cinemas', time: '12:01 AM', date: config.firstDay.date, lang: 'Tamil' }], { test: true });
          res.writeHead(200);
          return res.end('first-day drill started: alarm now + 3 repeats every 30s');
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
  log(`First-day watch: new shows on ${config.firstDay.date} before ${config.firstDay.beforeHour}:00 (or earlier dates) repeat ${config.firstDay.repeats}x every ${config.firstDay.repeatEverySeconds}s | WhatsApp numbers: ${whatsappRecipients().length} | Telegram: ${tgToken() ? Object.keys(tgLoad().chats).length + ' chats' : 'off'}${DRY_RUN ? ' | DRY RUN' : ''}`);
  log(`Watching ${baseUrl} every ${config.pollSeconds}s | FCM topic "${config.fcmTopic}" | ntfy "${config.ntfyTopic || '-'}"`);
  getFcm();
  startServer();
  let lastStart = Date.now();
  await runOnce();
  if (args.includes('--once')) return;
  for (;;) {
    // Start each check pollSeconds after the previous one started (plus a little jitter).
    const wait = config.pollSeconds * 1000 - (Date.now() - lastStart) + Math.floor(Math.random() * 2000);
    await sleep(Math.max(1000, wait));
    lastStart = Date.now();
    await runOnce();
  }
}

main();
