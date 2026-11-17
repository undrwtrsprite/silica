// Silica site: shared behaviour and animations. Every block below only runs when its element is on the page.
(() => {
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const fmt = (n, dec) => dec ? n.toFixed(dec) : Math.round(n).toLocaleString('en-US');
  const onView = (el, fn, opts = { threshold: .25 }) => {
    if (!('IntersectionObserver' in window)) { fn(); return; }
    const o = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) { o.disconnect(); fn(); } }, opts);
    o.observe(el);
  };
  const whileVisible = (el, cb) => {   // cb(true/false) as the element enters and leaves the screen
    if (!('IntersectionObserver' in window)) { cb(true); return; }
    new IntersectionObserver(es => cb(es[0].isIntersecting)).observe(el);
  };
  const hash = n => { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); };
  const hash2 = (x, y) => hash(x * 57.3 + y * 131.9);
  const vnoise = x => { const i = Math.floor(x), f = x - i, u = f * f * (3 - 2 * f); return hash(i) * (1 - u) + hash(i + 1) * u; };
  const vnoise2 = (x, y) => {
    const ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy, ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
    const a = hash2(ix, iy), b = hash2(ix + 1, iy), c = hash2(ix, iy + 1), d = hash2(ix + 1, iy + 1);
    return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy;
  };
  const fitCanvas = (c, ctx) => { const r = c.getBoundingClientRect(), d = Math.min(2, devicePixelRatio || 1); c.width = Math.max(1, Math.round(r.width * d)); c.height = Math.max(1, Math.round(r.height * d)); ctx.setTransform(d, 0, 0, d, 0, 0); return [r.width, r.height]; };

  // ---------- landing on a #section ----------
  // Nothing above the target changes height after load (the release list reserves its rows), so the browser's own jump is
  // enough. Content you land on skips its intro animation, otherwise it would slide while you are reading it.
  if (location.hash) {
    document.documentElement.classList.add('landing');
    setTimeout(() => document.documentElement.classList.remove('landing'), 1800);
  }
  const realign = () => {};   // kept as a hook: the releases list calls it, and it is deliberately a no-op now

  // ---------- the nav marks where you are, including a section inside a page (FAQ) ----------
  const navLinks = $$('.masthead nav a'), faqLink = navLinks.find(a => (a.getAttribute('href') || '').endsWith('#faq')), faqEl = $('#faq');
  const pageLink = navLinks.find(a => a.getAttribute('aria-current') === 'page');
  if (faqLink && faqEl && pageLink) {
    const mark = on => {
      faqLink.toggleAttribute('aria-current', false);
      pageLink.removeAttribute('aria-current');
      (on ? faqLink : pageLink).setAttribute('aria-current', on ? 'location' : 'page');
    };
    const spy = () => mark(faqEl.getBoundingClientRect().top < innerHeight * .5);
    addEventListener('scroll', spy, { passive: true });
    faqLink.addEventListener('click', () => mark(true));
    spy();
  }

  // ---------- top bar, links, pointer light on cards ----------
  const bar = $('.masthead');
  if (bar) { const solid = () => bar.classList.toggle('solid', scrollY > 24); addEventListener('scroll', solid, { passive: true }); solid(); }
  // any link to a section of the page you are already on (#faq, or /download#faq while on /download) scrolls there smoothly
  const samePage = p => p.replace(/\.html$/, '').replace(/\/$/, '') === location.pathname.replace(/\.html$/, '').replace(/\/$/, '');
  $$('a[href*="#"]').forEach(a => a.addEventListener('click', e => {
    const u = new URL(a.getAttribute('href'), location.href);
    if (!u.hash || !samePage(u.pathname)) return;
    const t = document.getElementById(decodeURIComponent(u.hash.slice(1)));
    if (!t) return;
    e.preventDefault();
    t.scrollIntoView({ behavior: still ? 'auto' : 'smooth', block: 'start' });
    history.replaceState(null, '', u.pathname + u.hash);
  }));
  document.addEventListener('pointermove', e => {
    const c = e.target.closest && e.target.closest('.card');
    if (!c) return;
    const r = c.getBoundingClientRect();
    c.style.setProperty('--mx', (e.clientX - r.left) + 'px'); c.style.setProperty('--my', (e.clientY - r.top) + 'px');
  }, { passive: true });

  // ---------- numbers count up once ----------
  const countUp = el => {
    const to = parseFloat(el.dataset.to), dec = +(el.dataset.dec || 0);
    if (still) { el.textContent = fmt(to, dec); return; }
    const t0 = performance.now(), dur = 1100;
    const tick = now => { const p = Math.min(1, (now - t0) / dur); el.textContent = fmt(to * (1 - Math.pow(1 - p, 3)), dec); if (p < 1) requestAnimationFrame(tick); };
    el.textContent = fmt(0, dec); requestAnimationFrame(tick);
    setTimeout(() => { el.textContent = fmt(to, dec); }, dur + 150);
  };
  $$('[data-reveal]').forEach(el => { if (still) { el.classList.add('in'); return; } onView(el, () => { el.classList.add('in'); $$('.count', el).forEach(countUp); }, { threshold: .3 }); });

  // ---------- downloads: the real count from GitHub, live ----------
  const dl = $('#dl-count');
  const releasesBox = $('#releases');
  if (dl || releasesBox) {
    fetch('https://api.github.com/repos/undrwtrsprite/silica/releases?per_page=100', { headers: { Accept: 'application/vnd.github+json' } })
      .then(r => r.ok ? r.json() : Promise.reject())
      .then(rels => {
        const dmgs = r => r.assets.filter(a => a.name.endsWith('.dmg')).reduce((t, a) => t + a.download_count, 0);
        if (dl) { const n = rels.reduce((s, r) => s + dmgs(r), 0); if (n > 0) { dl.dataset.to = n; dl.textContent = n.toLocaleString('en-US'); } }
        if (releasesBox) {
          releasesBox.innerHTML = rels.filter(r => !r.draft).slice(0, 6).map((r, i) => {
            const when = new Date(r.published_at).toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' });
            const n = dmgs(r);
            return `<a class="rel card${i ? '' : ' latest'}" href="${r.html_url}"><span class="tag">${r.tag_name}</span><span class="when">${when}</span><span class="dls">${n} download${n === 1 ? '' : 's'}</span><svg class="ico" aria-hidden="true"><use href="#i-arrow"/></svg></a>`;
          }).join('');
          realign();   // the list is taller now, so put a #faq or other target back where the visitor expects it
        }
      }).catch(() => {});
  }

  // ---------- the tilted launcher eases through the viewport ----------
  const stages = $$('[data-stage]');
  if (stages.length && !still) {
    const tilt = () => {
      const vh = innerHeight;
      stages.forEach(s => {
        const r = s.getBoundingClientRect();
        const p = Math.min(1, Math.max(0, (vh - r.top) / (vh + r.height)));
        s.style.setProperty('--p', p.toFixed(3)); s.style.setProperty('--t', Math.min(1, Math.abs(p - .5) * 2.2).toFixed(3));
      });
    };
    let q = false;
    addEventListener('scroll', () => { if (!q) { q = true; requestAnimationFrame(() => { q = false; tilt(); }); } }, { passive: true });
    addEventListener('resize', tilt); tilt();
  }
  if (still) $$('video[autoplay]').forEach(v => { v.removeAttribute('autoplay'); v.pause(); v.setAttribute('controls', ''); });

  // ---------- pictures load in like chunks ----------
  if (!still) $$('[data-chunks]').forEach(el => {
    const c = document.createElement('canvas'); c.className = 'chunks'; c.setAttribute('aria-hidden', 'true'); el.appendChild(c);
    const ctx = c.getContext('2d');
    let W, H, cells = [], cell;
    const build = () => {
      [W, H] = fitCanvas(c, ctx);
      cell = Math.max(16, Math.round(W / 26));
      cells = [];
      const cols = Math.ceil(W / cell), rows = Math.ceil(H / cell), cx = cols / 2, cy = rows / 2;
      for (let y = 0; y < rows; y++) for (let x = 0; x < cols; x++) cells.push({ x, y, k: Math.hypot(x - cx, y - cy) + hash2(x, y) * 4, t: Infinity });
      cells.sort((a, b) => a.k - b.k);
      ctx.fillStyle = '#0d1014'; ctx.fillRect(0, 0, W, H);
    };
    build();
    onView(el, () => {
      const t0 = performance.now(), dur = 950;
      const step = now => {
        const p = Math.min(1, (now - t0) / dur), n = Math.floor(cells.length * (1 - Math.pow(1 - p, 2)));
        ctx.clearRect(0, 0, W, H);
        cells.forEach((cl, i) => {
          if (i >= n) { ctx.fillStyle = '#0d1014'; ctx.fillRect(cl.x * cell, cl.y * cell, cell, cell); return; }
          if (cl.t === Infinity) cl.t = now;
          const age = now - cl.t;
          if (age < 260) { ctx.fillStyle = `rgba(123,201,111,${.28 * (1 - age / 260)})`; ctx.fillRect(cl.x * cell + 1, cl.y * cell + 1, cell - 2, cell - 2); }
        });
        if (p < 1 || cells.some(cl => now - cl.t < 260)) requestAnimationFrame(step); else c.remove();
      };
      requestAnimationFrame(step);
    }, { threshold: .2 });
  });

  // ---------- pixel terrain band ----------
  const terrain = (i, layer) => layer ? .25 + .55 * (vnoise(i * .03 + 40) * .7 + vnoise(i * .09 + 9) * .3) : .12 + .62 * (vnoise(i * .045) * .6 + vnoise(i * .13 + 7) * .3 + vnoise(i * .35 + 3) * .1);
  $$('canvas[data-band]').forEach(c => {
    const ctx = c.getContext('2d'), px = 8;
    let W = 0, H = 0, cols = 0, raf = 0, vis = false, t0 = 0, last = 0;
    const size = () => { [W, H] = fitCanvas(c, ctx); cols = Math.ceil(W / px) + 1; };
    const draw = now => {
      const t = still ? 99999 : now - t0, scroll = still ? 0 : t * .008;
      ctx.clearRect(0, 0, W, H);
      const maxRows = Math.floor(H / px);
      for (let layer = 1; layer >= 0; layer--) {
        const sc = scroll * (layer ? .55 : 1);
        for (let i = 0; i < cols; i++) {
          const wi = i + Math.floor(sc) + (layer ? 500 : 0);
          const grow = Math.min(1, Math.max(0, (t - i * 7 - layer * 250) / 900)), eased = 1 - Math.pow(1 - grow, 3);
          const rows = Math.max(1, Math.round(terrain(wi, layer) * maxRows * eased));
          const x = i * px - (sc % 1) * px;
          for (let r = 0; r < rows; r++) {
            const depth = rows - 1 - r, y = H - (r + 1) * px, j = hash(wi * 13.7 + r * 3.1);
            let color;
            if (layer) color = `rgba(123,201,111,${depth === 0 ? .16 : .05 + j * .03})`;
            else if (depth === 0) color = `rgba(${123 + j * 25 | 0},${201 + j * 20 | 0},111,${.78 + j * .12})`;
            else if (depth < 3) color = `rgba(${96 + j * 24 | 0},${112 + j * 20 | 0},64,${.32 - depth * .05})`;
            else color = `rgba(${110 + j * 30 | 0},${84 + j * 20 | 0},58,${Math.max(.02, .17 - depth * .012 + j * .03)})`;
            ctx.fillStyle = color; ctx.fillRect(x, y, px - 1, px - 1);
          }
          if (!layer && rows > 2 && grow >= 1 && hash(wi * 7.3 + Math.floor(t / 900)) > .985) { ctx.fillStyle = 'rgba(255,255,255,.55)'; ctx.fillRect(x, H - rows * px, px - 1, px - 1); }
        }
      }
    };
    const loop = now => { if (!vis) { raf = 0; return; } if (now - last > 33) { last = now; draw(now); } raf = requestAnimationFrame(loop); };
    size();
    if (still) { draw(0); addEventListener('resize', () => { size(); draw(0); }); return; }
    addEventListener('resize', size);
    whileVisible(c, v => { vis = v; if (v && !t0) t0 = performance.now(); if (v && !raf) raf = requestAnimationFrame(loop); });
  });

  // ---------- a moody valley at dusk (roadmap hero) ----------
  // Layers of blocky terrain recede into fog. Far layers use bigger blocks and near ones smaller, the way distant terrain gets coarser in the game.
  const dusk = $('canvas.dusk');
  if (dusk) {
    const ctx = dusk.getContext('2d');
    const LAYERS = [   // d: depth (0 far, 1 near), bs: block size in px, base/amp: where the skyline sits, f: ridge frequency
      { d: .06, bs: 22, base: .50, amp: .20, f: .8, seed: 3, col: [36, 70, 74], fog: .82 },
      { d: .16, bs: 15, base: .57, amp: .17, f: 1.1, seed: 11, col: [26, 54, 60], fog: .66 },
      { d: .30, bs: 11, base: .65, amp: .14, f: 1.5, seed: 23, col: [18, 40, 46], fog: .48 },
      { d: .52, bs: 8, base: .76, amp: .10, f: 2.1, seed: 37, col: [11, 28, 32], fog: .30 },
      { d: .80, bs: 6, base: .88, amp: .08, f: 3.0, seed: 51, col: [7, 17, 20], fog: .12 }
    ];
    let W = 0, H = 0, raf = 0, vis = false, t0 = 0, last = 0, mx = 0, tmx = 0, flies = [], torches = [], shoot = null, nextShoot = 6000;
    const skyline = (L, wx) => L.base - L.amp * (vnoise(wx * .06 * L.f + L.seed) * .62 + vnoise(wx * .17 * L.f + L.seed * 2) * .28 + vnoise(wx * .5 * L.f + L.seed * 3) * .1) * 1.5 + L.amp * .5;
    const size = () => {
      [W, H] = fitCanvas(dusk, ctx);
      flies = Array.from({ length: Math.round(Math.min(34, W / 42)) }, (_, i) => ({ x: hash(i * 3.3) * W, y: H * (.6 + hash(i * 7.1) * .32), p: hash(i * 5.9) * 6.28, s: .4 + hash(i * 1.7) * .9 }));
    };
    const draw = now => {
      const t = still ? 5200 : now - t0;
      const breath = .5 + .5 * Math.sin(t / 7000);                       // the horizon glow slowly swells and fades
      mx += (tmx - mx) * .06;
      // sky
      let g = ctx.createLinearGradient(0, 0, 0, H * .78);
      g.addColorStop(0, '#05070d'); g.addColorStop(.45, '#0a1620'); g.addColorStop(.8, '#16363a'); g.addColorStop(1, '#27504a');
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
      // stars, single pixels, a few brighter
      for (let i = 0; i < 110; i++) {
        const x = Math.floor(hash(i * 12.9) * W), y = Math.floor(hash(i * 4.3) * H * .5), tw = still ? 1 : .35 + .65 * Math.abs(Math.sin(t / 900 + i * 1.7));
        ctx.fillStyle = `rgba(210,230,240,${((.18 + hash(i * 2.2) * .5) * tw).toFixed(3)})`; ctx.fillRect(x, y, i % 11 === 0 ? 3 : 2, i % 11 === 0 ? 3 : 2);
      }
      // a rare shooting star
      if (!still) {
        if (!shoot && t > nextShoot) { shoot = { x: W * (.1 + Math.random() * .5), y: H * (.06 + Math.random() * .16), t: t }; nextShoot = t + 9000 + Math.random() * 9000; }
        if (shoot) { const k = (t - shoot.t) / 900; if (k > 1) shoot = null; else for (let j = 0; j < 12; j++) { const kk = Math.max(0, k - j * .018); ctx.fillStyle = `rgba(235,245,255,${(.8 * (1 - j / 12) * (1 - k)).toFixed(3)})`; ctx.fillRect(shoot.x + kk * W * .28, shoot.y + kk * H * .16, 3, 3); } }
      }
      // the glow where the dawn is on its way
      const gx = W * .68 + mx * -40, gy = H * .6;
      let rg = ctx.createRadialGradient(gx, gy, 0, gx, gy, W * .5);
      rg.addColorStop(0, `rgba(232,160,90,${.16 + .12 * breath})`); rg.addColorStop(.35, `rgba(150,170,110,${.07 + .05 * breath})`); rg.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);
      // layers, far to near
      LAYERS.forEach((L, li) => {
        const drift = still ? 0 : t * .0009 * (.25 + L.d * 1.6), off = -mx * L.d * 34, oy = 0;   // no scroll-linked movement: the scene stays put while the page scrolls past it
        const cols = Math.ceil(W / L.bs) + 3;
        let minY = H;
        ctx.fillStyle = `rgb(${L.col[0]},${L.col[1]},${L.col[2]})`;
        const tops = [];
        for (let i = -1; i < cols; i++) {
          const wx = i + Math.floor(drift), y = Math.round((skyline(L, wx) * H + oy) / L.bs) * L.bs;
          tops.push([i, wx, y]); if (y < minY) minY = y;
          ctx.fillRect(i * L.bs - (drift % 1) * L.bs + off, y, L.bs + 1, H - y + 2);
        }
        if (li === 4) torches = tops.filter(([i, wx]) => ((wx % 130) + 130) % 130 === 60).map(([i, wx, y]) => [i * L.bs - (drift % 1) * L.bs + off + L.bs / 2, y, L.bs]);
        // moonlit edge on the nearer layers: one lit block along the top, some columns brighter
        if (li >= 2) tops.forEach(([i, wx, y]) => {
          const j = hash(wx * 3.1 + li), a = (.1 + li * .06) * (.5 + j);
          ctx.fillStyle = `rgba(123,201,111,${a.toFixed(3)})`; ctx.fillRect(i * L.bs - (drift % 1) * L.bs + off, y, L.bs + 1, Math.max(2, L.bs * .3));
          if (li >= 3 && hash(wx * 1.7 + li * 9) > .93) {   // a tree: a dark trunk and a blocky crown
            const bx = i * L.bs - (drift % 1) * L.bs + off, bs = L.bs;
            ctx.fillStyle = `rgb(${L.col[0] - 2},${L.col[1] - 4},${L.col[2] - 4})`;
            ctx.fillRect(bx + bs, y - bs * 3, bs, bs * 3); ctx.fillRect(bx - bs, y - bs * 5, bs * 5, bs * 2); ctx.fillRect(bx, y - bs * 6, bs * 3, bs);
          }
        });
        // distance fog settles on the layer and lifts toward the near ones
        const fy = Math.max(0, minY - 90), fg = ctx.createLinearGradient(0, fy, 0, H);
        fg.addColorStop(0, 'rgba(40,86,84,0)'); fg.addColorStop(.22, `rgba(40,86,84,${(L.fog * .5).toFixed(3)})`); fg.addColorStop(.6, `rgba(24,54,56,${(L.fog * .28).toFixed(3)})`); fg.addColorStop(1, 'rgba(10,20,24,0)');
        ctx.fillStyle = fg; ctx.fillRect(0, fy, W, H - fy);
        // the lake sits in the valley after the third layer and reflects the glow
        if (li === 2) {
          const wy = H * .76;
          let wg = ctx.createLinearGradient(0, wy, 0, H);
          wg.addColorStop(0, 'rgba(46,92,92,.92)'); wg.addColorStop(1, 'rgba(8,18,22,.98)');
          ctx.fillStyle = wg; ctx.fillRect(0, wy, W, H - wy);
          const rows = Math.floor((H - wy) / 6);
          for (let r = 0; r < rows; r++) {
            const yy = wy + r * 6, k = r / rows, sh = still ? 0 : Math.sin(t / 700 + r * 1.9);
            const len = (W * (.08 + .22 * hash(r * 3.3))) * (1 - k * .3), x0 = W * .5 + (hash(r * 6.1) - .5) * W * .8 + sh * 14 + mx * -30 * (1 - k);
            ctx.fillStyle = `rgba(232,170,100,${((.05 + .09 * breath) * (1 - k) * (.6 + .4 * sh)).toFixed(3)})`; ctx.fillRect(x0, yy, len, 2);
            ctx.fillStyle = `rgba(140,200,190,${(.04 * (1 - k)).toFixed(3)})`; ctx.fillRect(x0 + len * .5, yy + 3, len * .7, 1);
          }
        }
      });
      // drifting mist
      for (let m = 0; m < 3; m++) {
        const my = H * (.52 + m * .13), w = W * (.7 + m * .15), x = ((t * .006 * (m + 1) + m * 420) % (W + w)) - w + mx * -20, a = .06 + m * .015;
        let mg = ctx.createLinearGradient(x, 0, x + w, 0);
        mg.addColorStop(0, 'rgba(120,170,165,0)'); mg.addColorStop(.5, `rgba(120,170,165,${a})`); mg.addColorStop(1, 'rgba(120,170,165,0)');
        ctx.fillStyle = mg; ctx.fillRect(x, my - 26, w, 52);
      }
      // torches planted on the nearest hill: they stand on the ground and scroll past with it, each flickering on its own
      torches.forEach(([tx0, gy, bs], k) => {
        const tx = Math.round(tx0), ty = Math.round(gy), u = bs / 6;                    // sized from the hill's own block size: about a block tall
        const fl = still ? 1 : .75 + .25 * Math.sin(t / 90 + k * 2) * Math.sin(t / 231 + 1 + k) + .1 * Math.sin(t / 47 + k);
        if (tx < -120 || tx > W + 120) return;
        const R = 46 * u * fl;
        let tg = ctx.createRadialGradient(tx, ty - 4 * u, 0, tx, ty - 4 * u, R);
        tg.addColorStop(0, `rgba(255,170,80,${(.24 * fl).toFixed(3)})`); tg.addColorStop(.5, `rgba(230,130,60,${(.08 * fl).toFixed(3)})`); tg.addColorStop(1, 'rgba(0,0,0,0)');
        ctx.fillStyle = tg; ctx.fillRect(tx - R, ty - 4 * u - R, R * 2, R * 2);
        ctx.fillStyle = '#4a3320'; ctx.fillRect(tx - u, ty - 5 * u, 2 * u, 5 * u);     // the post, resting on the top block
        ctx.fillStyle = `rgba(255,${(150 + 60 * fl) | 0},60,1)`; ctx.fillRect(tx - u, ty - 7.5 * u, 2 * u, 2.5 * u);
        ctx.fillStyle = 'rgba(255,235,160,.95)'; ctx.fillRect(tx - u * .5, ty - 7 * u, u, u * 1.5);
      });
      // fireflies
      flies.forEach(f => {
        const x = f.x + Math.sin(t / 2300 * f.s + f.p) * 40 + mx * -26, y = f.y + Math.cos(t / 1900 * f.s + f.p * 1.7) * 18;
        const b = still ? .8 : Math.pow(Math.max(0, Math.sin(t / 700 * f.s + f.p)), 2);
        ctx.fillStyle = `rgba(190,235,120,${(.12 * b).toFixed(3)})`; ctx.fillRect(x - 5, y - 5, 10, 10);
        ctx.fillStyle = `rgba(225,250,150,${(.9 * b).toFixed(3)})`; ctx.fillRect(x - 1, y - 1, 3, 3);
      });
      // a little darkness at the edges keeps the text readable
      let vg = ctx.createLinearGradient(0, 0, W, 0);
      vg.addColorStop(0, 'rgba(7,10,14,.55)'); vg.addColorStop(.55, 'rgba(7,10,14,.12)'); vg.addColorStop(1, 'rgba(7,10,14,0)');
      ctx.fillStyle = vg; ctx.fillRect(0, 0, W, H);
    };
    const loop = now => { if (!vis) { raf = 0; return; } if (now - last > 33) { last = now; draw(now); } raf = requestAnimationFrame(loop); };
    size();
    if (still) { draw(0); addEventListener('resize', () => { size(); draw(0); }); }
    else {
      addEventListener('resize', size);
      dusk.parentElement.addEventListener('pointermove', e => { const r = dusk.getBoundingClientRect(); tmx = ((e.clientX - r.left) / r.width - .5) * 2; }, { passive: true });
      whileVisible(dusk, v => { vis = v; if (v && !t0) t0 = performance.now(); if (v && !raf) raf = requestAnimationFrame(loop); });
    }
  }

  // ---------- the roadmap tree: tiles, redstone wires, and a detail panel ----------
  const treegrid = $('#treegrid');
  if (treegrid) {
    const svg = $('.wires', treegrid), btns = $$('.nbtn', treegrid), byId = Object.fromEntries(btns.map(b => [b.dataset.id, b]));
    const edges = JSON.parse(treegrid.dataset.edges);
    const detail = $('#detail');
    const wires = () => {
      const tr = treegrid.getBoundingClientRect();
      svg.setAttribute('viewBox', `0 0 ${tr.width} ${tr.height}`);
      svg.innerHTML = edges.map(([a, b]) => {
        const ta = $('.tile', byId[a]).getBoundingClientRect(), tb = $('.tile', byId[b]).getBoundingClientRect();
        const x1 = ta.right - tr.left, y1c = ta.top + ta.height / 2 - tr.top, x2 = tb.left - tr.left, y2 = tb.top + tb.height / 2 - tr.top;
        const y1 = Math.abs(y2 - y1c) > 4 ? y1c + Math.sign(y2 - y1c) * 9 : y1c, mid = x1 + (x2 - x1) * .5;   // wires leaving the same tile fan out instead of stacking
        const st = byId[b].dataset.state, cls = st === 'done' || st === 'now' ? 'on' : st === 'exp' ? 'exp' : '';
        return `<path class="w ${cls}" d="M${x1.toFixed(1)} ${y1.toFixed(1)}H${mid.toFixed(1)}V${y2.toFixed(1)}H${x2.toFixed(1)}"/>`;
      }).join('');
    };
    const chipClass = { done: 'done', now: 'now', exp: 'exp', next: 'next', later: 'next' };
    const select = b => {
      btns.forEach(x => x.setAttribute('aria-pressed', String(x === b)));
      $('#d-chip').textContent = b.dataset.status; $('#d-chip').className = 'chip ' + chipClass[b.dataset.state];
      $('#d-title').textContent = $('.nt', b).textContent; $('#d-plain').textContent = b.dataset.plain; $('#d-tech').textContent = b.dataset.tech;
      detail.classList.remove('swap'); void detail.offsetWidth; if (!still) detail.classList.add('swap');
    };
    btns.forEach(b => b.addEventListener('click', () => select(b)));
    const first = byId.frames; if (first) select(first);
    wires();
    addEventListener('resize', wires);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(wires);
    if ('ResizeObserver' in window) new ResizeObserver(wires).observe(treegrid);
  }

  // ---------- the floating voxel island (home) ----------
  const isle = $('#island');
  if (isle) {
    const ctx = isle.getContext('2d'), N = 22, hint = $('#island-count');
    const MAT = {   // top, side colour
      grass: [[112, 186, 86], [134, 96, 64]], dirt: [[134, 96, 64], [134, 96, 64]], stone: [[128, 133, 140], [118, 122, 130]],
      snow: [[236, 241, 245], [128, 133, 140]], sand: [[219, 203, 143], [204, 186, 126]], water: [[70, 132, 210], [58, 112, 186]],
      log: [[150, 116, 74], [104, 78, 48]], leaves: [[74, 150, 64], [58, 124, 52]]
    };
    const cols = [];
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      const dx = (x - N / 2 + .5) / (N / 2), dy = (y - N / 2 + .5) / (N / 2), d = Math.hypot(dx, dy);
      if (d > .86 + vnoise2(x * .35, y * .35) * .22) continue;
      let h = Math.round(1.5 + (1 - d) * 5 + vnoise2(x * .22 + 4, y * .22 + 9) * 6 - 1.5);
      let mat = 'grass';
      if (h <= 2) { h = 2; mat = 'water'; } else if (h === 3) mat = 'sand'; else if (h >= 10) mat = 'snow'; else if (h >= 8) mat = 'stone';
      const bottom = -Math.round(1 + (1 - d) * 7 + hash2(x, y) * 2);
      const tree = mat === 'grass' && d < .72 && hash2(x + 3, y + 7) > .9;
      cols.push({ x, y, d, top: h, base: h, bottom, mat, tree, delay: d * 900 + hash2(x, y) * 300, pop: -1e9 });
    }
    cols.sort((a, b) => (a.x + a.y) - (b.x + b.y) || a.x - b.x);
    let W = 0, H = 0, tw = 0, bh = 0, cx = 0, y0 = 0, t0 = performance.now(), hover = null, placed = 0, vis = false, raf = 0, last = 0;
    const layout = () => {
      [W, H] = fitCanvas(isle, ctx);
      tw = Math.min(W / (N + 1), H / (N * .5 + 11));
      bh = tw * .55; cx = W / 2;
      y0 = H / 2 - (N * tw / 2) / 2 + 1.5 * bh;
    };
    const P = (x, y, z) => [cx + (x - y) * tw / 2, y0 + (x + y) * tw / 4 - z * bh];
    const shade = (c, k, a = 1) => `rgba(${c[0] * k | 0},${c[1] * k | 0},${c[2] * k | 0},${a})`;
    const prism = (x, y, z0, z1, mat, dy, alpha, scale = 1, topExtra = 0) => {
      const [sx, sy0] = P(x + .5, y + .5, z1); const sy = sy0 + dy, hw = tw / 2 * scale, hh = tw / 4 * scale, depth = (z1 - z0) * bh;
      const [top, side] = MAT[mat];
      // left and right faces in bands: the top block keeps its own side colour (grass shows green on top of dirt)
      const faces = [[-1, .78], [1, .6]];
      faces.forEach(([dir, k]) => {
        ctx.beginPath();
        ctx.moveTo(sx + dir * hw, sy); ctx.lineTo(sx, sy + hh); ctx.lineTo(sx, sy + hh + depth); ctx.lineTo(sx + dir * hw, sy + depth); ctx.closePath();
        ctx.fillStyle = shade(z1 <= 0 ? MAT.stone[1] : side, k * (z0 < 0 ? .78 : 1), alpha); ctx.fill();
        if (mat === 'grass' && depth > 0) {   // a strip of grass along the top edge of the side
          ctx.beginPath(); ctx.moveTo(sx + dir * hw, sy); ctx.lineTo(sx, sy + hh); ctx.lineTo(sx, sy + hh + bh * .28); ctx.lineTo(sx + dir * hw, sy + bh * .28); ctx.closePath();
          ctx.fillStyle = shade(MAT.grass[0], k * .85, alpha); ctx.fill();
        }
        if (z0 < 0 && z1 > 0 && mat !== 'stone') {   // stone below ground level
          const g = z1 * bh;
          ctx.beginPath(); ctx.moveTo(sx + dir * hw, sy + g); ctx.lineTo(sx, sy + hh + g); ctx.lineTo(sx, sy + hh + depth); ctx.lineTo(sx + dir * hw, sy + depth); ctx.closePath();
          ctx.fillStyle = shade(MAT.stone[1], k * .8, alpha); ctx.fill();
        }
        ctx.strokeStyle = `rgba(0,0,0,${.12 * alpha})`; ctx.lineWidth = 1; ctx.beginPath();
        for (let i = 1; i < z1 - z0; i++) { const yy = sy + i * bh; ctx.moveTo(sx + dir * hw, yy); ctx.lineTo(sx, yy + hh); }
        ctx.stroke();
      });
      ctx.beginPath(); ctx.moveTo(sx, sy - hh); ctx.lineTo(sx + hw, sy); ctx.lineTo(sx, sy + hh); ctx.lineTo(sx - hw, sy); ctx.closePath();
      const j = hash2(x * 3 + z1, y * 5);
      ctx.fillStyle = shade(top, (.92 + j * .14) + topExtra, alpha); ctx.fill();
      return [sx, sy, hw, hh, depth];
    };
    const draw = now => {
      const t = still ? 1e6 : now - t0, bob = still ? 0 : Math.sin(t / 1700) * 4;
      ctx.clearRect(0, 0, W, H);
      // a soft shadow far below
      const [shx, shy] = P(N / 2, N / 2, -14);
      const g = ctx.createRadialGradient(shx, shy, 0, shx, shy, tw * N * .45);
      g.addColorStop(0, 'rgba(0,0,0,.35)'); g.addColorStop(1, 'rgba(0,0,0,0)'); ctx.fillStyle = g; ctx.beginPath(); ctx.ellipse(shx, shy, tw * N * .45, tw * N * .14, 0, 0, Math.PI * 2); ctx.fill();
      let tops = [];
      cols.forEach(c => {
        const p = Math.min(1, Math.max(0, (t - c.delay) / 750)); if (p <= 0) return;
        const e = 1 - Math.pow(1 - p, 3) + Math.sin(p * Math.PI) * .06, dy = (1 - e) * -H * .5 + bob;
        const popping = now - c.pop < 320;
        const shimmer = c.mat === 'water' && !still ? Math.sin(t / 520 + c.x * .8 + c.y * .55) * .08 : 0;
        const z1 = popping ? c.top - 1 : c.top;
        let r = prism(c.x, c.y, c.bottom, z1, c.mat, dy, p, 1, shimmer + (hover === c ? .14 : 0));
        if (popping) { const q = (now - c.pop) / 320, fall = (1 - (1 - Math.pow(1 - q, 3))) * -tw * 1.6; r = prism(c.x, c.y, c.top - 1, c.top, c.mat, dy + fall, 1, 1, hover === c ? .14 : 0); }
        if (c.tree) { prism(c.x, c.y, c.top, c.top + 3, 'log', dy, p, .38); prism(c.x, c.y, c.top + 3, c.top + 5, 'leaves', dy, p, 1.0); }
        tops.push([c, r]);
      });
      if (hover) {
        const hit = tops.find(([c]) => c === hover);
        if (hit) { const [sx, sy, hw, hh] = hit[1]; ctx.beginPath(); ctx.moveTo(sx, sy - hh); ctx.lineTo(sx + hw, sy); ctx.lineTo(sx, sy + hh); ctx.lineTo(sx - hw, sy); ctx.closePath(); ctx.strokeStyle = 'rgba(255,255,255,.9)'; ctx.lineWidth = 2; ctx.stroke(); }
      }
      // drifting clouds, flat and blocky, above everything
      for (let k = 0; k < 2; k++) {
        const span = N + 14, ox = ((t / 1000 * .45 + k * 13.1) % span) - 7, oy = 5 + k * 8;
        const fade = Math.min(1, Math.min(ox + 7, span - 7 - ox) / 4) * Math.min(1, t / 2500);
        for (let i = 0; i < 6; i++) for (let jj = 0; jj < 3; jj++) {
          if (hash2(i + k * 11, jj) < .3) continue;
          const [sx, sy] = P(ox + i, oy + jj, 15); const hw = tw / 2, hh = tw / 4;
          ctx.beginPath(); ctx.moveTo(sx, sy - hh); ctx.lineTo(sx + hw, sy); ctx.lineTo(sx, sy + hh); ctx.lineTo(sx - hw, sy); ctx.closePath();
          ctx.fillStyle = `rgba(245,248,250,${.55 * fade})`; ctx.fill();
          ctx.beginPath(); ctx.moveTo(sx - hw, sy); ctx.lineTo(sx, sy + hh); ctx.lineTo(sx, sy + hh + bh * .5); ctx.lineTo(sx - hw, sy + bh * .5); ctx.closePath();
          ctx.fillStyle = `rgba(200,208,216,${.4 * fade})`; ctx.fill();
        }
      }
      isle._tops = tops;
    };
    const pick = (mx, my) => {
      const tops = isle._tops || [];
      // the front-most column whose outline (top face and both sides) contains the point
      for (let i = tops.length - 1; i >= 0; i--) {
        const [c, [sx, sy, hw, hh, depth]] = tops[i], dx = Math.abs(mx - sx);
        if (dx > hw) continue;
        const edge = hh * (1 - dx / hw);
        if (my >= sy - edge && my <= sy + depth + edge) return c;
      }
      return null;
    };
    const pos = e => { const r = isle.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
    isle.addEventListener('pointermove', e => { hover = pick(...pos(e)); if (still) draw(performance.now()); });
    isle.addEventListener('pointerleave', () => { hover = null; if (still) draw(performance.now()); });
    isle.addEventListener('click', e => {
      const c = pick(...pos(e)); if (!c || c.top >= 16) return;
      if (c.mat === 'water' || c.mat === 'sand') c.mat = c.mat === 'water' ? 'dirt' : c.mat;
      c.tree = false; c.top += 1; c.pop = performance.now(); placed++;
      if (hint) hint.textContent = placed.toLocaleString('en-US');
      if (still) draw(performance.now());
    });
    layout();
    addEventListener('resize', () => { layout(); if (still) draw(0); });
    const loop = now => { if (!vis) { raf = 0; return; } if (now - last > 30) { last = now; draw(now); } raf = requestAnimationFrame(loop); };
    if (still) draw(0);
    else whileVisible(isle, v => { vis = v; if (v && !raf) raf = requestAnimationFrame(loop); });
  }

  // ---------- features hero backdrop: Bayer-dithered pixel aurora, cratered moon & cross stars ----------
  const featBg = $('canvas.feat-bg');
  if (featBg) {
    const ctx = featBg.getContext('2d', { alpha: false });
    const PX = 4; // crisp 4x4 screen pixels per art pixel
    const BAYER = [
      [0, 8, 2, 10],
      [12, 4, 14, 6],
      [3, 11, 1, 9],
      [15, 7, 13, 5]
    ];
    let pw = 0, ph = 0, img = null, buf = null, raf = 0, vis = false, t0 = 0, last = 0, mx = 0, tmx = 0;
    let stars = [];

    const size = () => {
      const r = featBg.getBoundingClientRect();
      pw = Math.max(120, Math.round(r.width / PX));
      ph = Math.max(80, Math.round(r.height / PX));
      featBg.width = pw;
      featBg.height = ph;
      img = ctx.createImageData(pw, ph);
      buf = img.data;
      stars = Array.from({ length: 42 }, (_, i) => ({
        x: Math.floor(hash(i * 17.3) * pw),
        y: Math.floor(hash(i * 9.1) * ph * .58),
        cross: i % 5 === 0,
        ph: hash(i * 5.7) * 6.28,
        sp: .8 + hash(i * 3.1) * 1.6
      }));
    };

    const draw = now => {
      const t = (still ? 3200 : now - t0) * .001;
      mx += (tmx - mx) * .06;
      const moonCx = Math.round(pw * .92 - mx * 3);
      const moonCy = Math.round(ph * .18);
      const moonR = Math.max(4, Math.round(Math.min(pw, ph) * .034));

      for (let y = 0; y < ph; y++) {
        const ny = y / ph;
        const bRow = BAYER[y & 3];
        // base sky gradient (#0a0d11 -> #0c171b -> #0a0d11 at bottom)
        const skyV = Math.max(0, 1 - Math.abs(ny - .55) * 1.9);
        const fadeOut = ny > .84 ? Math.max(0, 1 - (ny - .84) / .16) : 1;

        for (let x = 0; x < pw; x++) {
          const nx = x / pw;
          const b = (bRow[x & 3] + .5) / 16;
          const textDim = .48 + .52 * Math.min(1, nx * 1.15);

          // two rolling aurora wave ribbons across the mid/lower hero
          const w1 = .60 + .11 * Math.sin(nx * 5.2 + t * .42 + mx * .8) + .05 * Math.cos(nx * 10.5 - t * .3);
          const w2 = .70 + .09 * Math.cos(nx * 4.4 - t * .34 - mx * .6) + .04 * Math.sin(nx * 9.0 + t * .25);
          const d1 = Math.exp(-Math.pow((ny - w1) / .14, 2));
          const d2 = Math.exp(-Math.pow((ny - w2) / .12, 2));

          // subtle diagonal shimmer curtain along the waves
          const curtain = .7 + .3 * Math.sin(nx * 18 - t * .7 + ny * 6);
          const aurora1 = d1 * curtain * (.65 + .35 * Math.sin(nx * 3.1 + t * .2)) * textDim;
          const aurora2 = d2 * (.6 + .4 * Math.cos(nx * 4.5 - t * .25)) * textDim;

          // moon halo
          const mdx = x - moonCx, mdy = y - moonCy;
          const mDist = Math.hypot(mdx, mdy);
          const halo = Math.exp(-mDist / (moonR * 3.2)) * .34;

          // ordered dither quantize for crisp retro banding
          const a1 = aurora1 * fadeOut;
          const a2 = aurora2 * fadeOut;
          const band1 = Math.min(1, Math.floor(a1 * 5 + b) / 5);
          const band2 = Math.min(1, Math.floor(a2 * 4 + b) / 4);
          const hBand = Math.min(1, Math.floor((halo + skyV * .14) * 4 + b) / 4);

          // Silica palette: deep slate #0a0d11 + emerald #7bc96f + teal #2e7a66 + warm amber #e2a94b accent
          let r = 10 + hBand * 16 + band1 * 68 + band2 * 96;
          let g = 13 + hBand * 32 + band1 * 134 + band2 * 74;
          let bl = 17 + hBand * 34 + band1 * 80 + band2 * 36;

          // square cratered pixel moon
          if (Math.abs(mdx) <= moonR && Math.abs(mdy) <= moonR) {
            const c1 = (mdx === -2 && mdy === -1) || (mdx === 2 && mdy === 2) || (mdx === 1 && mdy === -2);
            const c2 = (mdx === -1 && mdy === 2) || (mdx === 0 && mdy === 0);
            if (c1) { r = 165; g = 192; bl = 160; }
            else if (c2) { r = 186; g = 212; bl = 178; }
            else { r = 226; g = 244; bl = 216; }
          }

          const idx = (y * pw + x) * 4;
          buf[idx] = Math.min(255, r | 0);
          buf[idx + 1] = Math.min(255, g | 0);
          buf[idx + 2] = Math.min(255, bl | 0);
          buf[idx + 3] = 255;
        }
      }

      // twinkling single-pixel & 4-point cross stars
      const put = (sx, sy, br) => {
        if (sx < 0 || sx >= pw || sy < 0 || sy >= ph) return;
        const idx = (sy * pw + sx) * 4;
        buf[idx] = Math.min(255, buf[idx] + (185 * br) | 0);
        buf[idx + 1] = Math.min(255, buf[idx + 1] + (225 * br) | 0);
        buf[idx + 2] = Math.min(255, buf[idx + 2] + (205 * br) | 0);
      };
      stars.forEach(s => {
        const tw = still ? .85 : .25 + .75 * Math.pow(.5 + .5 * Math.sin(t * s.sp + s.ph), 2);
        const sx = (s.x + Math.round(mx * 2) + pw) % pw;
        put(sx, s.y, tw);
        if (s.cross && tw > .45) {
          put(sx - 1, s.y, tw * .55); put(sx + 1, s.y, tw * .55);
          put(sx, s.y - 1, tw * .55); put(sx, s.y + 1, tw * .55);
        }
      });

      ctx.putImageData(img, 0, 0);
    };

    const loop = now => {
      if (!vis) { raf = 0; return; }
      if (now - last > 33) { last = now; draw(now); }
      raf = requestAnimationFrame(loop);
    };

    size();
    if (still) {
      draw(0);
      addEventListener('resize', () => { size(); draw(0); });
    } else {
      addEventListener('resize', size);
      featBg.parentElement.addEventListener('pointermove', e => {
        const r = featBg.getBoundingClientRect();
        tmx = ((e.clientX - r.left) / r.width - .5) * 2;
      }, { passive: true });
      whileVisible(featBg, v => {
        vis = v;
        if (v && !t0) t0 = performance.now();
        if (v && !raf) raf = requestAnimationFrame(loop);
      });
    }
  }

  // ---------- render distance map (performance) ----------
  const map = $('#rdmap');
  if (map) {
    const ctx = map.getContext('2d'), R = 98, S = R * 2 + 1, px = 4;
    map.width = map.height = S * px;
    const slider = $('#rd'), lodBox = $('#lod'), out = { rd: $('#rd-val'), blocks: $('#rd-blocks'), total: $('#rd-total') };
    let rd = +slider.value, lod = lodBox.checked, shown = rd, shownLod = lod ? 64 : 0, from = { r: 0, l: 0 }, t0 = 0, anim = 0;
    const tierSize = e => e < 8 ? 2 : e < 16 ? 4 : e < 32 ? 8 : 16;   // illustration: the far ring is drawn coarser further out
    const draw = (rReal, rLod, now) => {
      ctx.fillStyle = '#06080b'; ctx.fillRect(0, 0, map.width, map.height);
      for (let gy = -R; gy <= R; gy++) for (let gx = -R; gx <= R; gx++) {
        const d = Math.hypot(gx, gy), X = (gx + R) * px, Y = (gy + R) * px;
        if (d <= rReal) {
          const j = hash2(gx, gy), front = rReal - d < 1.2 && rReal < rd + .01 && now - t0 < 900;
          ctx.fillStyle = front ? 'rgba(200,255,190,.95)' : `rgba(${110 + j * 30 | 0},${186 + j * 30 | 0},${86 + j * 20 | 0},${.8 + j * .2})`;
          ctx.fillRect(X, Y, px - 1, px - 1);
        } else if (rLod > 0 && d <= rReal + rLod) {
          const s = tierSize(d - rReal), j = hash2(Math.floor(gx / s), Math.floor(gy / s)), edge = ((gx % s) + s) % s === 0 || ((gy % s) + s) % s === 0;
          const a = .62 - (d - rReal) / (rLod + 1) * .42 + (edge ? .1 : 0);
          ctx.fillStyle = `rgba(${74 + j * 40 | 0},${130 + j * 40 | 0},${70 + j * 20 | 0},${a})`;
          ctx.fillRect(X, Y, px, px);
        } else if ((gx + gy) % 2 === 0) { ctx.fillStyle = 'rgba(255,255,255,.035)'; ctx.fillRect(X + 1, Y + 1, px - 2, px - 2); }
      }
      // the original game's Far setting: 256 blocks, 16 chunks
      ctx.strokeStyle = 'rgba(226,169,75,.95)'; ctx.setLineDash([6, 6]); ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc((R + .5) * px, (R + .5) * px, 16 * px, 0, Math.PI * 2); ctx.stroke(); ctx.setLineDash([]);
      // you
      const blink = still ? 1 : (Math.sin(now / 250) > 0 ? 1 : .4);
      ctx.fillStyle = `rgba(255,255,255,${blink})`; ctx.fillRect(R * px - px, R * px - px, px * 3, px * 3);
    };
    const update = () => {
      rd = +slider.value; lod = lodBox.checked;
      slider.style.setProperty('--fill', ((rd - 2) / 30 * 100) + '%');
      out.rd.innerHTML = `${rd}<small>chunks</small>`;
      out.blocks.innerHTML = `${(rd * 16).toLocaleString('en-US')}<small>blocks</small>`;
      out.total.innerHTML = lod ? `${rd + 64}<small>chunks with LODs</small>` : `${rd}<small>chunks, LODs off</small>`;
      from = { r: shown, l: shownLod }; t0 = performance.now();
      if (still) { shown = rd; shownLod = lod ? 64 : 0; draw(shown, shownLod, 0); return; }
      if (!anim) anim = requestAnimationFrame(tick);
    };
    const tick = now => {
      const p = Math.min(1, (now - t0) / 800), e = 1 - Math.pow(1 - p, 3);
      shown = from.r + (rd - from.r) * e; shownLod = from.l + ((lod ? 64 : 0) - from.l) * e;
      draw(shown, shownLod, now);
      anim = requestAnimationFrame(tick);
    };
    slider.addEventListener('input', update); lodBox.addEventListener('change', update);
    shown = 0; shownLod = 0;
    draw(0, 0, 0);
    if (still) update(); else setTimeout(update, 450);   // the map sits in the page's first screen, so it loads its chunks straight away
  }

  // ---------- benchmarks: real runs from the project's own harness (bench/results) ----------
  const grid = $('#mgrid');
  const BENCH = {
    short: { name: 'Short', gl: { fps: 482.29, low: 179.78, p99: 5.562, max: 21.076, mem: 652, s: [542, 556, 522, 498, 415, 447, 370, 388, 496] }, vk: { fps: 2808.52, low: 777.98, p99: 1.285, max: 16.741, mem: 677, s: [3215, 3300, 3043, 2837, 2434, 2523, 2384, 2307, 2636] } },
    normal: { name: 'Normal', gl: { fps: 247.13, low: 121.97, p99: 8.199, max: 11.959, mem: 885, s: [252, 281, 296, 244, 208, 228, 238, 237, 239] }, vk: { fps: 1835.52, low: 608.87, p99: 1.642, max: 5.769, mem: 760, s: [2187, 2021, 1874, 1731, 1346, 1634, 1524, 1772, 1953] } },
    far: { name: 'Far', gl: { fps: 120.96, low: 67.53, p99: 14.809, max: 45.674, mem: 1077, s: [123, 130, 105, 85, 81, 115, 134, 148, 147] }, vk: { fps: 1129.32, low: 388.22, p99: 2.576, max: 7.066, mem: 933, s: [1088, 1139, 959, 884, 692, 966, 1129, 1360, 1606] } }
  };
  let current = 'far', setFeel = () => {};
  if (grid) {
    const METRICS = [
      { title: 'Frames per second', hint: 'Higher is better', groups: [{ k: 'fps', label: 'On average', unit: 'FPS' }, { k: 'low', label: 'In the worst 1% of frames', unit: 'FPS' }] },
      { title: 'Frame time', hint: 'Lower is better', groups: [{ k: 'p99', label: 'Typical worst frame, 99th percentile', unit: 'ms', dec: 1 }, { k: 'max', label: 'Longest frame', unit: 'ms', dec: 1 }] },
      { title: 'Memory', hint: 'Whole process, lower is better', groups: [{ k: 'mem', label: 'In use while playing', unit: 'MB' }], mem: true }
    ];
    const rowHtml = (cls, name, id) => `<div class="mrow ${cls}"><div class="top"><span class="name"><i class="dot"></i>${name}</span><span class="v" data-v="${id}">0<small></small></span></div><div class="mtrack"><div class="mfill" data-f="${id}"></div></div></div>`;
    let html = '';
    METRICS.forEach(m => {
      html += `<div class="mcard card" data-r><h3>${m.title}</h3><p class="hint">${m.hint}</p>`;
      m.groups.forEach(g => { html += `<div class="mgroup"><h4>${g.label}</h4>${rowHtml('vk', 'Vulkan', g.k + '-vk')}${rowHtml('gl', 'OpenGL baseline', g.k + '-gl')}</div>`; });
      if (m.mem) html += `<div class="honest" id="honest" hidden><svg class="ico" aria-hidden="true"><use href="#i-info"/></svg><span id="honest-t"></span></div>`;
      html += '</div>';
    });
    html += `<div class="mcard card" data-r><h3>Every second of the run</h3><p class="hint">Frames per second, measured second by second</p>
      <svg class="spark" id="spark" viewBox="0 0 420 170" role="img" aria-label="Frames per second for each measured second, Vulkan and the OpenGL baseline">
        <line class="grid" x1="0" y1="20" x2="420" y2="20"/><line class="grid" x1="0" y1="85" x2="420" y2="85"/><line class="grid" x1="0" y1="150" x2="420" y2="150"/>
        <path class="ln gl" id="ln-gl" pathLength="1"/><path class="ln vk" id="ln-vk" pathLength="1"/></svg>
      <div class="spark-legend"><span>second 1</span><span id="spark-max"></span><span>second 9</span></div></div>`;
    grid.insertAdjacentHTML('beforeend', html);
    $$('.mcard', grid).forEach((c, i) => c.style.setProperty('--i', i));
    const val = (d, k, dec) => dec ? d[k].toFixed(dec) : Math.round(d[k]).toLocaleString('en-US');
    const setBench = key => {
      current = key; const B = BENCH[key];
      $$('[data-d]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.d === key)));
      METRICS.forEach(m => m.groups.forEach(g => {
        const max = Math.max(B.gl[g.k], B.vk[g.k]);
        ['vk', 'gl'].forEach(s => { $(`[data-v="${g.k}-${s}"]`).innerHTML = `${val(B[s], g.k, g.dec)}<small>${g.unit}</small>`; $(`[data-f="${g.k}-${s}"]`).style.setProperty('--w', (B[s][g.k] / max * 100).toFixed(1) + '%'); });
      }));
      $('#verdict').innerHTML = `On ${B.name}, Vulkan runs <b>${Math.round(B.vk.fps).toLocaleString('en-US')} FPS</b> against the OpenGL baseline's ${Math.round(B.gl.fps).toLocaleString('en-US')}, about <b>${(B.vk.fps / B.gl.fps).toFixed(1)}×</b> the frame rate.`;
      const honest = $('#honest');
      if (B.gl.mem < B.vk.mem) { honest.hidden = false; $('#honest-t').textContent = `The OpenGL baseline uses a little less memory here: ${B.gl.mem} MB against ${B.vk.mem} MB.`; } else honest.hidden = true;
      const top = Math.max(...B.gl.s, ...B.vk.s) * 1.08;
      const path = s => s.map((v, i) => `${i ? 'L' : 'M'}${(i / (s.length - 1) * 420).toFixed(1)} ${(150 - v / top * 130).toFixed(1)}`).join(' ');
      const sp = $('#spark'); sp.classList.remove('drawn'); $('#ln-gl').setAttribute('d', path(B.gl.s)); $('#ln-vk').setAttribute('d', path(B.vk.s));
      $('#spark-max').textContent = `up to ${Math.round(Math.max(...B.vk.s)).toLocaleString('en-US')} FPS`;
      void sp.getBoundingClientRect(); requestAnimationFrame(() => sp.classList.add('drawn'));
      setFeel();
    };
    $$('[data-d]').forEach(b => b.addEventListener('click', () => setBench(b.dataset.d)));
    if (still) setBench(current); else onView(grid, () => setBench(current), { threshold: .15 });
  }

  // ---------- slow motion: the same pan at each renderer's measured frame rate ----------
  const feel = $('.feel');
  if (feel) {
    const fc = { gl: $('#feel-gl'), vk: $('#feel-vk') }, img = new Image(); img.src = 'assets/shot-lod.jpg';
    let slow = 20, paused = false, frames = { gl: -1, vk: -1 }, fps = { gl: 121, vk: 1129 }, vis = false, raf = 0, simT = 0, lastNow = 0;
    const drawFeel = sim => {
      if (!img.complete || !img.naturalWidth) return;
      ['gl', 'vk'].forEach(k => {
        const f = Math.floor(sim * fps[k]); if (f === frames[k]) return; frames[k] = f;
        const c = fc[k], x = c.getContext('2d'), iw = img.naturalWidth, ih = img.naturalHeight, sh = ih * .62, sw = sh * (c.width / c.height), span = iw - sw;
        const pos = f / fps[k] * 60, m = pos % (span * 2), off = m < span ? m : span * 2 - m;
        x.drawImage(img, off, ih * .08, sw, sh, 0, 0, c.width, c.height);
        $(`#feel-${k}-n`).textContent = f.toLocaleString('en-US') + ' frames';
      });
    };
    setFeel = () => {
      const B = BENCH[current]; fps = { gl: B.gl.fps, vk: B.vk.fps };
      $('#feel-gl-fps').textContent = Math.round(fps.gl).toLocaleString('en-US') + ' FPS'; $('#feel-vk-fps').textContent = Math.round(fps.vk).toLocaleString('en-US') + ' FPS';
      frames = { gl: -1, vk: -1 }; simT = 0; drawFeel(0);
    };
    const loop = now => { if (!vis) { raf = 0; return; } if (!paused) { simT += Math.min(.1, (now - lastNow) / 1000) / slow; drawFeel(simT); } lastNow = now; raf = requestAnimationFrame(loop); };
    $$('[data-slow]').forEach(b => b.addEventListener('click', () => { slow = +b.dataset.slow; $$('[data-slow]').forEach(x => x.setAttribute('aria-pressed', String(x === b))); frames = { gl: -1, vk: -1 }; simT = 0; }));
    $('#feel-pause').addEventListener('click', e => { paused = !paused; e.currentTarget.textContent = paused ? 'Play' : 'Pause'; });
    img.addEventListener('load', () => drawFeel(0));
    if (still) { paused = true; $('#feel-pause').hidden = true; } else whileVisible(feel, v => { vis = v; if (v && !raf) { lastNow = performance.now(); raf = requestAnimationFrame(loop); } });
    setFeel();
  }

  // ---------- demo video: one element; the buttons seek or swap the recording ----------
  const v = $('#demo-video');
  if (v) {
    const btns = $$('[data-src]');
    const files = { launcher: ['assets/launcher-demo.mp4', 'assets/poster-demo.jpg'], benchmark: ['assets/benchmark.mp4', 'assets/poster-bench.jpg'] };
    let cur = v.dataset.start || 'launcher';
    const mark = b => btns.forEach(c => c.setAttribute('aria-pressed', String(c === b)));
    const playAt = t => { const go = () => { v.currentTime = t; v.play().catch(() => {}); }; if (v.readyState >= 3) return go(); v.addEventListener('canplay', go, { once: true }); v.play().catch(() => {}); };
    btns.forEach(b => b.addEventListener('click', () => {
      const which = b.dataset.src, t = parseFloat(b.dataset.t); mark(b);
      if (which !== cur) { cur = which; v.poster = files[which][1]; v.loop = which === 'launcher'; v.src = files[which][0]; }
      playAt(t);
    }));
    v.addEventListener('timeupdate', () => {
      if (cur !== 'launcher') return;
      const starts = btns.filter(c => c.dataset.src === 'launcher'); let hit = starts[0];
      starts.forEach(c => { if (v.currentTime >= parseFloat(c.dataset.t)) hit = c; }); mark(hit);
    });
  }

  // ---------- rise-in for headings, rows, tiles (last, once the page has built its DOM) ----------
  $$('section.block h2, details, .rows .row > div:not(.stage), .split-head, .sect-sub').forEach(el => el.setAttribute('data-r', ''));
  $$('.grid6, .faq-cols > div, .links-grid, .doors, .reqs, .steps, .howto').forEach(g => [...g.children].forEach((c, i) => { c.style.setProperty('--i', Math.min(i, 5)); c.setAttribute('data-r', ''); }));
  const rise = $$('[data-r]');
  if ('IntersectionObserver' in window && !still) {
    const rio = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('r-in'); rio.unobserve(e.target); } }), { threshold: .01, rootMargin: '0px 0px 80px 0px' });
    rise.forEach(el => rio.observe(el));
  } else rise.forEach(el => el.classList.add('r-in'));
})();
