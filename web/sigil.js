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

  function start(canvas, getState) {
    const ctx = canvas.getContext('2d');
    let t = 0, last = performance.now(), pulseStart = -1, lastPulseKey = null;
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
      const cx = W / 2, cy = H / 2, r = Math.min(W, H) / 2 * 0.92, s = dpr;
      ctx.clearRect(0, 0, W, H);

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
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  window.DoomSigil = { start };
})();
