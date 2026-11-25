// The Doom sigil: counter-rotating rings of procedurally drawn runes, a turning
// five-point star, a breathing core, and drifting sparks. Same design as the Android app.
(function () {
  const GREEN = { deep: '#0F6E56', mid: '#1D9E75', bright: '#5DCAA5', glow: '#9FE1CB' };
  const RED = { deep: '#791F1F', mid: '#A32D2D', bright: '#E24B4A', glow: '#F7C1C1' };
  const TAU = Math.PI * 2;

  function rune(ctx, x, y, h, seed, color, lw) {
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = lw;
    ctx.lineCap = 'round';
    const w = h * 0.45;
    ctx.beginPath();
    ctx.moveTo(x, y - h / 2);
    ctx.lineTo(x, y + h / 2);
    ctx.stroke();
    const bits = (Math.imul(seed, 2654435761) >>> 3) >>> 0;
    const ys = [-0.5, -0.2, 0.1];
    for (let k = 0; k < 3; k++) {
      const b = (bits >>> (k * 3)) & 7;
      const y0 = y + ys[k] * h;
      const line = (dx, dy) => { ctx.beginPath(); ctx.moveTo(x, y0); ctx.lineTo(x + dx, y0 + dy); ctx.stroke(); };
      if (b <= 1) line(w, h * 0.25);
      else if (b <= 3) line(-w, h * 0.25);
      else if (b === 4) { line(w, h * 0.2); line(-w, h * 0.2); }
      else if (b === 5) { ctx.beginPath(); ctx.arc(x + w * 0.6, y0 + h * 0.1, h * 0.08, 0, TAU); ctx.fill(); }
    }
  }

  const GOLD = '#EF9F27', GOLD_DEEP = '#854F0B', GOLD_LIGHT = '#FAC775';
  const THRONE = '#0C1210', CAPE = '#0B2A20', CAPE_EDGE = '#1D9E75';

  function poly(ctx, u, pts) {
    ctx.beginPath();
    pts.forEach(([x, y], i) => (i ? ctx.lineTo(x * u, y * u) : ctx.moveTo(x * u, y * u)));
    ctx.closePath();
  }

  // The god of mischief on his throne (original drawing, no studio art).
  function throne(ctx, u, t) {
    ctx.save();
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    poly(ctx, u, [[30, 44], [30, 16], [33, 9], [36, 15], [40, 6], [44, 13], [50, 0.5], [56, 13], [60, 6], [64, 15], [67, 9], [70, 16], [70, 44]]);
    ctx.fillStyle = THRONE; ctx.fill();
    ctx.strokeStyle = GOLD_DEEP; ctx.lineWidth = 0.5 * u; ctx.stroke();
    ctx.globalAlpha = 0.6; ctx.lineWidth = 0.3 * u;
    [40, 50, 60].forEach((x) => { ctx.beginPath(); ctx.moveTo(x * u, 16 * u); ctx.lineTo(x * u, 40 * u); ctx.stroke(); });
    ctx.globalAlpha = 1;
    [-1, 1].forEach((sd) => {
      const x0 = 50 + sd * 20;
      poly(ctx, u, [[x0 - 4, 34], [x0 + 4, 34], [x0 + 3, 44], [x0 - 3, 44]]);
      ctx.fillStyle = THRONE; ctx.fill();
      ctx.lineWidth = 0.4 * u; ctx.strokeStyle = GOLD_DEEP; ctx.stroke();
    });
    // Cape flaring behind the arms.
    ctx.beginPath(); ctx.moveTo(45 * u, 23 * u);
    ctx.bezierCurveTo(38 * u, 26 * u, 31 * u, 34 * u, 27 * u, 45 * u);
    ctx.lineTo(73 * u, 45 * u);
    ctx.bezierCurveTo(69 * u, 34 * u, 62 * u, 26 * u, 55 * u, 23 * u);
    ctx.closePath();
    ctx.fillStyle = '#07170F'; ctx.fill();
    ctx.globalAlpha = 0.35; ctx.strokeStyle = CAPE_EDGE; ctx.lineWidth = 0.3 * u; ctx.stroke(); ctx.globalAlpha = 1;
    // Seated body.
    ctx.beginPath(); ctx.moveTo(44.5 * u, 22.5 * u);
    ctx.quadraticCurveTo(50 * u, 20.5 * u, 55.5 * u, 22.5 * u);
    ctx.lineTo(61 * u, 44 * u);
    ctx.quadraticCurveTo(50 * u, 46 * u, 39 * u, 44 * u);
    ctx.closePath();
    ctx.fillStyle = CAPE; ctx.fill();
    ctx.globalAlpha = 0.8; ctx.strokeStyle = CAPE_EDGE; ctx.lineWidth = 0.45 * u; ctx.stroke(); ctx.globalAlpha = 1;
    ctx.strokeStyle = GOLD; ctx.lineWidth = 0.6 * u;
    ctx.beginPath(); ctx.moveTo(46 * u, 23 * u); ctx.lineTo(54 * u, 23 * u); ctx.stroke();
    // Arms holding the timelines.
    const sway = Math.sin(t * 0.8) * 0.6;
    const lh = [33 * u, (27 + sway) * u], rh = [67 * u, (27 - sway) * u];
    [[45, lh], [55, rh]].forEach(([sx, h]) => {
      ctx.strokeStyle = CAPE; ctx.lineWidth = 2.2 * u;
      ctx.beginPath(); ctx.moveTo(sx * u, 24 * u); ctx.lineTo(h[0], h[1]); ctx.stroke();
      ctx.globalAlpha = 0.5; ctx.strokeStyle = CAPE_EDGE; ctx.lineWidth = 0.35 * u; ctx.stroke(); ctx.globalAlpha = 1;
    });
    const glow = 0.6 + 0.4 * Math.sin(t * 3);
    [lh, rh].forEach((h) => {
      ctx.fillStyle = GOLD_LIGHT;
      ctx.globalAlpha = 0.25 * glow; ctx.beginPath(); ctx.arc(h[0], h[1], 3.2 * u, 0, TAU); ctx.fill();
      ctx.globalAlpha = 1; ctx.beginPath(); ctx.arc(h[0], h[1], 1.1 * u, 0, TAU); ctx.fill();
    });
    // Helmet.
    ctx.beginPath(); ctx.moveTo(46.6 * u, 16.2 * u);
    ctx.quadraticCurveTo(50 * u, 13.4 * u, 53.4 * u, 16.2 * u);
    ctx.lineTo(53.2 * u, 19.6 * u); ctx.lineTo(50 * u, 22.6 * u); ctx.lineTo(46.8 * u, 19.6 * u);
    ctx.closePath();
    ctx.fillStyle = '#101614'; ctx.fill();
    ctx.strokeStyle = GOLD_DEEP; ctx.lineWidth = 0.3 * u; ctx.stroke();
    // The great curved horns.
    [-1, 1].forEach((sd) => {
      ctx.beginPath(); ctx.moveTo((50 + sd * 1.5) * u, 15.6 * u);
      ctx.bezierCurveTo((50 + sd * 4) * u, 10 * u, (50 + sd * 7.5) * u, 5 * u, (50 + sd * 12.5) * u, 1.5 * u);
      ctx.bezierCurveTo((50 + sd * 8.5) * u, 6.5 * u, (50 + sd * 5.5) * u, 11 * u, (50 + sd * 3.4) * u, 16.4 * u);
      ctx.closePath();
      ctx.fillStyle = GOLD; ctx.fill();
      ctx.strokeStyle = GOLD_DEEP; ctx.lineWidth = 0.3 * u; ctx.stroke();
    });
    ctx.strokeStyle = GOLD; ctx.lineWidth = 0.8 * u;
    ctx.beginPath(); ctx.moveTo(47 * u, 16.4 * u); ctx.lineTo(53 * u, 16.4 * u); ctx.stroke();
    ctx.fillStyle = '#9FE1CB'; ctx.globalAlpha = (t % 5) > 4.85 ? 0.1 : 1;
    [48.7, 51.3].forEach((x) => { ctx.beginPath(); ctx.arc(x * u, 18.4 * u, 0.45 * u, 0, TAU); ctx.fill(); });
    ctx.restore();
  }

  function bez(p0, p1, p2, p3, s) {
    const m = 1 - s;
    return [0, 1].map((i) => p0[i] * m * m * m + p1[i] * 3 * m * m * s + p2[i] * 3 * m * s * s + p3[i] * s * s * s);
  }

  // Timelines flowing from his hands, branching, winding around the sigil.
  function timelines(ctx, u, t, c) {
    ctx.save();
    ctx.lineCap = 'round';
    [-1, 1].forEach((sd) => {
      const hand = [(50 + sd * 17) * u, 27 * u];
      for (let k = 0; k < 5; k++) {
        const w = Math.sin(t * 0.7 + k * 1.3 + sd) * 2.2;
        const p1 = [(50 + sd * (30 + k * 3.5) + w) * u, (12 - k * 2.5) * u];
        const p2 = [(50 + sd * (60 + k * 2.5)) * u, (80 + k * 7 + w) * u];
        const p3 = [(50 + sd * (5 + k * 4.5)) * u, (139 - k * 1.2) * u];
        const col = (k + (sd > 0 ? 1 : 0)) % 2 === 0 ? GOLD : c.bright;
        ctx.beginPath(); ctx.moveTo(hand[0], hand[1]);
        ctx.bezierCurveTo(p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]);
        ctx.strokeStyle = col;
        ctx.globalAlpha = 0.09; ctx.lineWidth = 1.5 * u; ctx.stroke();
        ctx.globalAlpha = 0.6; ctx.lineWidth = (0.22 + 0.05 * k) * u; ctx.stroke();
        if (k % 2 === 1) {
          const b0 = bez(hand, p1, p2, p3, 0.45 + 0.05 * k);
          const be = [b0[0] + sd * (10 + k * 2) * u, b0[1] + (16 + Math.sin(t + k) * 3) * u];
          ctx.globalAlpha = 0.5; ctx.lineWidth = 0.3 * u;
          ctx.beginPath(); ctx.moveTo(b0[0], b0[1]);
          ctx.quadraticCurveTo(b0[0] + sd * 9 * u, b0[1] + 2 * u, be[0], be[1]); ctx.stroke();
          ctx.globalAlpha = 0.8; ctx.fillStyle = col;
          ctx.beginPath(); ctx.arc(be[0], be[1], 0.5 * u, 0, TAU); ctx.fill();
        }
        for (let j = 0; j < 3; j++) {
          const sp = (t * (0.06 + k * 0.01) + j / 3 + k * 0.17) % 1;
          const pt = bez(hand, p1, p2, p3, sp), a = Math.sin(sp * Math.PI);
          ctx.globalAlpha = 0.9 * a; ctx.fillStyle = GOLD_LIGHT;
          ctx.beginPath(); ctx.arc(pt[0], pt[1], (0.55 + 0.25 * a) * u, 0, TAU); ctx.fill();
        }
      }
    });
    ctx.restore();
  }

  function displace(a, b, depth, rough, out) {
    if (!depth) { out.push(b); return; }
    const dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy) || 1;
    const off = (Math.random() - 0.5) * len * rough;
    const m = [(a[0] + b[0]) / 2 - (dy / len) * off, (a[1] + b[1]) / 2 + (dx / len) * off];
    displace(a, m, depth - 1, rough, out);
    displace(m, b, depth - 1, rough, out);
  }

  function makeBolt(u, born) {
    let target;
    if (Math.random() < 0.25) {
      target = [(50 + (Math.random() < 0.5 ? -12.5 : 12.5)) * u, 1.5 * u];
    } else {
      const ang = Math.PI + Math.random() * Math.PI;
      target = [(50 + Math.cos(ang) * 44) * u, (90 + Math.sin(ang) * 44) * u];
    }
    const start = [(target[0] < 50 * u ? Math.random() * 25 : 75 + Math.random() * 25) * u, -4 * u];
    const main = [start];
    displace(start, target, 6, 0.32, main);
    const forks = [];
    const n = 1 + Math.floor(Math.random() * 3);
    for (let i = 0; i < n; i++) {
      const from = main[Math.floor(main.length / 4 + (Math.random() * main.length) / 2)];
      const end = [
        from[0] + (target[0] - start[0]) * (0.15 + Math.random() * 0.25) + (Math.random() - 0.5) * 30 * u,
        from[1] + (target[1] - start[1]) * (0.2 + Math.random() * 0.2),
      ];
      const f = [from];
      displace(from, end, 4, 0.4, f);
      forks.push(f);
    }
    return { main, forks, born, target };
  }

  function drawBolt(ctx, b, secs, u, W, H) {
    const age = secs - b.born;
    if (age > 0.9 || age < 0) return;
    const a = (1 - age / 0.9) * (0.7 + 0.3 * Math.sin(age * 70));
    ctx.save();
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    if (age < 0.15) { ctx.globalAlpha = 0.16 * (1 - age / 0.15); ctx.fillStyle = '#85B7EB'; ctx.fillRect(0, 0, W, H); }
    const line = (pts, sc) => {
      ctx.beginPath();
      pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
      [['#378ADD', 0.35, 4.5], ['#85B7EB', 0.85, 1.7], ['#F4F9FF', 1, 0.7], [GOLD_LIGHT, 0.35, 0.15]].forEach(([col, al, wd]) => {
        ctx.strokeStyle = col; ctx.globalAlpha = al * a; ctx.lineWidth = wd * u * sc; ctx.stroke();
      });
    };
    line(b.main, 1);
    b.forks.forEach((f) => line(f, 0.6));
    ctx.globalAlpha = 0.8 * a; ctx.fillStyle = '#F4F9FF';
    ctx.beginPath(); ctx.arc(b.target[0], b.target[1], 1.6 * u * (1 + age * 3), 0, TAU); ctx.fill();
    ctx.restore();
  }

  function start(canvas, getState) {
    const ctx = canvas.getContext('2d');
    let t = 0, last = performance.now(), pulseStart = -1, lastPulseKey = null;
    let bolts = [], nextBolt = 1.2, lastStrikeKey = null;
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function frame(now) {
      const st = getState();
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      if (!reduce) t += dt * st.speed;
      if (st.pulseKey && st.pulseKey !== lastPulseKey) { lastPulseKey = st.pulseKey; pulseStart = now; }

      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const W = canvas.clientWidth * dpr, H = canvas.clientHeight * dpr;
      if (canvas.width !== W || canvas.height !== H) { canvas.width = W; canvas.height = H; }
      const c = st.alarm ? RED : GREEN;
      const u = W / 100, cx = W / 2, cy = 90 * u, r = 46 * u, s = dpr;
      ctx.fillStyle = '#07090A';
      ctx.fillRect(0, 0, W, H);
      timelines(ctx, u, t, c);
      throne(ctx, u, now / 1000);

      // sparks
      for (let i = 0; i < 46; i++) {
        const sp = 0.05 + ((i * 37) % 23) / 23 * 0.09;
        const ph = (t * sp + i * 0.618) % 1;
        const x = cx + Math.sin(ph * 5 + i) * r * (0.2 + (i % 7) / 7 * 0.9);
        const y = cy + r * 1.1 - ph * r * 2.3;
        ctx.globalAlpha = Math.max(0, Math.sin(ph * Math.PI)) * 0.8;
        ctx.fillStyle = i % 3 === 0 ? c.glow : c.mid;
        ctx.beginPath(); ctx.arc(x, y, (1 + (i % 4) * 0.5) * s, 0, TAU); ctx.fill();
      }
      ctx.globalAlpha = 1;

      const ring = (rot, fn) => { ctx.save(); ctx.translate(cx, cy); ctx.rotate(rot); fn(); ctx.restore(); };
      const circle = (rad, color, lw) => { ctx.strokeStyle = color; ctx.lineWidth = lw; ctx.beginPath(); ctx.arc(0, 0, rad, 0, TAU); ctx.stroke(); };

      // outer tick ring
      ring(t * 6 * Math.PI / 180, () => {
        circle(r, c.deep, 1 * s);
        for (let i = 0; i < 72; i++) {
          const a = i / 72 * TAU, big = i % 6 === 0, len = big ? r * 0.07 : r * 0.03;
          ctx.strokeStyle = c.mid; ctx.lineWidth = (big ? 1.5 : 0.8) * s;
          ctx.beginPath(); ctx.moveTo(Math.cos(a) * r, Math.sin(a) * r); ctx.lineTo(Math.cos(a) * (r - len), Math.sin(a) * (r - len)); ctx.stroke();
        }
      });

      // main rune ring
      const runeR = r * 0.8;
      ring(-t * 10 * Math.PI / 180, () => {
        circle(runeR + r * 0.075, c.mid, 1.5 * s);
        circle(runeR - r * 0.075, c.mid, 0.8 * s);
        for (let i = 0; i < 18; i++) {
          ctx.save(); ctx.rotate(i / 18 * TAU);
          rune(ctx, 0, -runeR, r * 0.11, i * 7919 + 13, c.bright, 1.6 * s);
          ctx.restore();
        }
      });

      // inner rune ring
      const innerR = r * 0.58;
      ring(t * 16 * Math.PI / 180, () => {
        circle(innerR, c.deep, 1 * s);
        for (let i = 0; i < 12; i++) {
          ctx.save(); ctx.rotate(i / 12 * TAU);
          rune(ctx, 0, -innerR - r * 0.055, r * 0.07, i * 104729 + 7, c.mid, 1.4 * s);
          ctx.restore();
        }
      });

      // star
      ring(-t * 22 * Math.PI / 180, () => {
        const sr = r * 0.48;
        ctx.strokeStyle = c.bright; ctx.lineWidth = 2 * s; ctx.lineJoin = 'round';
        ctx.beginPath();
        for (let i = 0; i <= 5; i++) {
          const a = i * 2 * TAU / 5 - Math.PI / 2;
          i ? ctx.lineTo(Math.cos(a) * sr, Math.sin(a) * sr) : ctx.moveTo(Math.cos(a) * sr, Math.sin(a) * sr);
        }
        ctx.stroke();
        ctx.fillStyle = c.glow;
        for (let i = 0; i < 5; i++) {
          const a = i * TAU / 5 - Math.PI / 2;
          ctx.beginPath(); ctx.arc(Math.cos(a) * sr, Math.sin(a) * sr, 3 * s, 0, TAU); ctx.fill();
        }
      });

      // core
      const breath = 0.5 + 0.5 * Math.sin(t * 2.2), cr = r * (0.24 + 0.03 * breath);
      ctx.globalAlpha = 0.35; ctx.fillStyle = c.deep; ctx.beginPath(); ctx.arc(cx, cy, cr, 0, TAU); ctx.fill();
      ctx.globalAlpha = 1; ctx.strokeStyle = c.mid; ctx.lineWidth = 1.5 * s; ctx.beginPath(); ctx.arc(cx, cy, cr, 0, TAU); ctx.stroke();

      // shockwave after each watcher check
      if (pulseStart >= 0) {
        const p = (now - pulseStart) / 1600;
        if (p < 1) {
          ctx.globalAlpha = (1 - p) * 0.8; ctx.strokeStyle = c.glow; ctx.lineWidth = (3 * (1 - p) + 0.5) * s;
          ctx.beginPath(); ctx.arc(cx, cy, r * (0.25 + p * 0.85), 0, TAU); ctx.stroke(); ctx.globalAlpha = 1;
        }
      }
      // Thunder: forked bolts strike the sigil rim or the horns.
      const secs = now / 1000;
      if (secs >= nextBolt || (st.pulseKey && st.pulseKey !== lastStrikeKey)) {
        lastStrikeKey = st.pulseKey;
        bolts = bolts.filter((b) => secs - b.born < 0.9).concat([makeBolt(u, secs)]).slice(-3);
        nextBolt = secs + 2.5 + Math.random() * 4;
      }
      bolts.forEach((b) => drawBolt(ctx, b, secs, u, W, H));
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  window.DoomSigil = { start };
})();
