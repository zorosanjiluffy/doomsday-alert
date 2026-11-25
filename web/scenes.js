// Cycles the home scenes (Doom sigil → Thor → Loki, ~8 s each, tap to skip) and draws the
// live effects over the Thor and Loki pictures. Tilting the phone shifts the layers for depth.
(function () {
  const SCENE_MS = 8000;
  const TAU = Math.PI * 2;
  const stage = document.getElementById('stage');
  const scenes = [...stage.querySelectorAll('.scene')];
  const dots = [...stage.querySelectorAll('.dots i')];
  const flash = document.getElementById('flash');
  const fxThor = document.getElementById('fx-thor');
  const fxLoki = document.getElementById('fx-loki');
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let index = 0;
  let timer = null;

  function show(i) {
    index = i;
    scenes.forEach((s, k) => s.classList.toggle('active', k === i));
    dots.forEach((d, k) => d.classList.toggle('on', k === i));
    flash.className = 'flash';
    void flash.offsetWidth; // restart the animation
    if (i === 1) flash.classList.add('bolt');
    if (i === 2) flash.classList.add('mist');
    clearTimeout(timer);
    timer = setTimeout(() => show((index + 1) % 3), SCENE_MS);
  }

  // ---- tilt (iPhone asks permission on the first tap) ----
  let tilt = { x: 0, y: 0 }, target = { x: 0, y: 0 }, asked = false;
  function onOrient(e) {
    target.x = Math.max(-1, Math.min(1, (e.gamma || 0) / 25));
    target.y = Math.max(-1, Math.min(1, ((e.beta || 45) - 45) / 25));
  }
  function askTilt() {
    if (asked) return;
    asked = true;
    const D = window.DeviceOrientationEvent;
    if (D && typeof D.requestPermission === 'function') {
      D.requestPermission().then((r) => { if (r === 'granted') addEventListener('deviceorientation', onOrient); }).catch(() => {});
    } else {
      addEventListener('deviceorientation', onOrient);
    }
  }
  if (!(window.DeviceOrientationEvent && typeof DeviceOrientationEvent.requestPermission === 'function')) askTilt();

  stage.addEventListener('click', () => { askTilt(); show((index + 1) % 3); });

  // ---- helpers ----
  function fit(canvas) {
    const dpr = Math.min(2, devicePixelRatio || 1);
    const W = canvas.clientWidth * dpr, H = canvas.clientHeight * dpr;
    if (canvas.width !== W || canvas.height !== H) { canvas.width = W; canvas.height = H; }
    return [canvas.getContext('2d'), W, H];
  }
  // Picture fraction → canvas pixel, matching object-fit: cover.
  function mapper(W, H, aspect) {
    const dh = Math.max(W / aspect, H), dw = dh * aspect, ox = (W - dw) / 2, oy = (H - dh) / 2;
    return (fx, fy) => [ox + fx * dw, oy + fy * dh];
  }
  function rng(seed) { let s = seed >>> 0 || 1; return () => ((s = Math.imul(s ^ (s >>> 15), 2246822507) + 0x6d2b79f5 >>> 0) / 4294967296); }
  function displace(a, b, depth, rough, rand, out) {
    if (!depth) { out.push(b); return; }
    const dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy) || 1, off = (rand() - 0.5) * len * rough;
    const m = [(a[0] + b[0]) / 2 - (dy / len) * off, (a[1] + b[1]) / 2 + (dx / len) * off];
    displace(a, m, depth - 1, rough, rand, out);
    displace(m, b, depth - 1, rough, rand, out);
  }
  function boltPath(ctx, pts, u, sc, a) {
    ctx.beginPath();
    pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
    [['#378ADD', 0.35, 5], ['#85B7EB', 0.8, 1.8], ['#F4F9FF', 1, 0.7]].forEach(([c, al, w]) => {
      ctx.strokeStyle = c; ctx.globalAlpha = al * a; ctx.lineWidth = w * u * sc; ctx.stroke();
    });
    ctx.globalAlpha = 1;
  }
  function glow(ctx, x, y, r, inner, mid) {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, inner); if (mid) g.addColorStop(0.45, mid); g.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill();
  }
  function bez(a, c, b, s) { const m = 1 - s; return [a[0] * m * m + c[0] * 2 * m * s + b[0] * s * s, a[1] * m * m + c[1] * 2 * m * s + b[1] * s * s]; }

  // ---- Thor ----
  function drawThor(t) {
    const [ctx, W, H] = fit(fxThor), u = W / 100, p = mapper(W, H, 736 / 1308);
    ctx.clearRect(0, 0, W, H);
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    const fist = p(0.51, 0.545), sky = p(0.53, -0.05);
    const surge = 0.5 + 0.5 * Math.sin(t * 9) * Math.sin(t * 2.3);
    ctx.globalAlpha = 0.05 + 0.08 * surge; ctx.fillStyle = '#85B7EB'; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1;
    const seed = Math.floor(t * 12), rand = rng(seed + 1);
    const main = [sky]; displace(sky, fist, 7, 0.18, rand, main);
    boltPath(ctx, main, u, 1.2, 0.75 + 0.25 * surge);
    for (let i = 0; i < 3; i++) {
      const from = main[Math.floor(main.length / 5 + rand() * (main.length * 0.7))];
      const end = [from[0] + (rand() - 0.5) * 28 * u, from[1] + rand() * 10 * u], f = [from];
      displace(from, end, 4, 0.45, rand, f); boltPath(ctx, f, u, 0.5, 0.6);
    }
    for (let i = 0; i < 4; i++) {
      const r2 = rng(seed * 7 + i + 3), ang = r2() * TAU, d = (6 + r2() * 9) * u;
      const arc = [fist]; displace(fist, [fist[0] + Math.cos(ang) * d, fist[1] + Math.sin(ang) * d], 3, 0.5, r2, arc);
      boltPath(ctx, arc, u, 0.35, 0.7);
    }
    glow(ctx, fist[0], fist[1], (9 + 4 * surge) * u, 'rgba(244,249,255,0.95)', 'rgba(133,183,235,0.45)');
    for (let i = 0; i < 14; i++) {
      const ph = (t * (0.03 + (i % 5) * 0.008) + i * 0.137) % 1;
      const x = W * ((i * 0.618) % 1) + Math.sin(t * 0.6 + i) * 6 * u, y = H * (0.92 - ph * 0.75);
      const sz = (0.8 + (i % 3) * 0.5) * u, rot = t * (0.6 + (i % 4) * 0.3) + i, a = Math.sin(ph * Math.PI) * 0.9;
      ctx.beginPath();
      for (let v = 0; v < 3; v++) {
        const an = rot + v * 2.1 + (i % 2) * 0.4, r = sz * (v === 0 ? 1.6 : 1);
        v ? ctx.lineTo(x + Math.cos(an) * r, y + Math.sin(an) * r) : ctx.moveTo(x + Math.cos(an) * r, y + Math.sin(an) * r);
      }
      ctx.closePath(); ctx.globalAlpha = a; ctx.fillStyle = '#06101E'; ctx.fill();
      ctx.globalAlpha = a * 0.55; ctx.strokeStyle = '#85B7EB'; ctx.lineWidth = 0.2 * u; ctx.stroke(); ctx.globalAlpha = 1;
    }
  }

  // ---- Loki ----
  function drawLoki(t) {
    const [ctx, W, H] = fit(fxLoki), u = W / 100, p = mapper(W, H, 900 / 1592);
    ctx.clearRect(0, 0, W, H);
    ctx.lineCap = 'round';
    for (let i = 0; i < 7; i++) {
      const cx = W * ((((i * 0.31) + t * 0.012 * (1 + (i % 3))) % 1.2) - 0.1);
      const cy = H * (0.2 + ((i * 0.17) % 0.75)) + Math.sin(t * 0.3 + i) * 8 * u;
      glow(ctx, cx, cy, (22 + (i % 3) * 10) * u, 'rgba(93,202,165,0.16)');
    }
    const lh = p(0.32, 0.505), rh = p(0.735, 0.51), head = p(0.52, 0.39), strands = [];
    for (let k = 0; k < 4; k++) {
      strands.push([lh, p(0.10 - k * 0.02, 0.40 - k * 0.03), p(-0.02, 0.27 + k * 0.04)]);
      strands.push([lh, p(0.16 - k * 0.02, 0.66 + k * 0.03), p(-0.02, 0.78 + k * 0.04)]);
      strands.push([rh, p(0.90 + k * 0.02, 0.40 - k * 0.03), p(1.02, 0.30 + k * 0.04)]);
      strands.push([rh, p(0.86 + k * 0.02, 0.66 + k * 0.03), p(1.02, 0.76 + k * 0.04)]);
    }
    for (let k = 0; k < 6; k++) strands.push([head, p(0.40 + k * 0.04, 0.20), p(0.30 + k * 0.09, -0.02)]);
    strands.forEach(([a, c, b], i) => {
      for (let j = 0; j < 2; j++) {
        const s = (t * (0.22 + (i % 5) * 0.04) + j * 0.5 + i * 0.21) % 1, k = Math.sin(s * Math.PI);
        let prev = bez(a, c, b, Math.max(0, s - 0.07));
        for (let q = 1; q <= 6; q++) {
          const pt = bez(a, c, b, Math.max(0, s - 0.07 + q * (0.07 / 6))), f = q / 6;
          ctx.beginPath(); ctx.moveTo(prev[0], prev[1]); ctx.lineTo(pt[0], pt[1]);
          ctx.strokeStyle = '#C0DD97'; ctx.globalAlpha = 0.25 * k * f; ctx.lineWidth = 1.1 * u; ctx.stroke();
          ctx.strokeStyle = '#EAF3DE'; ctx.globalAlpha = 0.9 * k * f; ctx.lineWidth = 0.28 * u; ctx.stroke();
          prev = pt;
        }
      }
    });
    ctx.globalAlpha = 1;
    const pulse = 0.6 + 0.4 * Math.sin(t * 2.4);
    [lh, rh].forEach((h) => glow(ctx, h[0], h[1], (6 + 3 * pulse) * u, 'rgba(192,221,151,0.7)'));
    const sh = (t * 0.5) % 1;
    if (sh < 0.25) { const hp = p(0.52, 0.38); glow(ctx, hp[0], hp[1], 7 * u, `rgba(250,199,117,${0.5 * Math.sin(sh * 4 * Math.PI)})`); }
    for (let i = 0; i < 30; i++) {
      const ph = (t * (0.04 + (i % 5) * 0.01) + i * 0.19) % 1;
      ctx.globalAlpha = Math.sin(ph * Math.PI) * 0.7; ctx.fillStyle = '#C0DD97';
      ctx.beginPath(); ctx.arc(W * ((i * 0.377) % 1) + Math.sin(t + i) * 4 * u, H * (1 - ph), (0.35 + (i % 3) * 0.2) * u, 0, TAU); ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  // ---- loop ----
  function frame(now) {
    const t = reduce ? 0 : now / 1000;
    const drift = { x: Math.sin(t * 0.25) * 0.25, y: Math.sin(t * 0.19 + 1) * 0.2 };
    tilt.x += (target.x - tilt.x) * 0.1; tilt.y += (target.y - tilt.y) * 0.1;
    stage.style.setProperty('--tx', Math.max(-1, Math.min(1, tilt.x + drift.x)).toFixed(3));
    stage.style.setProperty('--ty', Math.max(-1, Math.min(1, tilt.y + drift.y)).toFixed(3));
    if (index === 1) drawThor(t);
    if (index === 2) drawLoki(t);
    requestAnimationFrame(frame);
  }
  show(0);
  requestAnimationFrame(frame);
})();
