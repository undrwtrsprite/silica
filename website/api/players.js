const crypto = require('node:crypto');

// Anonymous live player & runtime statistics endpoint.
// Uses Upstash Redis / Vercel KV when KV_REST_API_URL or UPSTASH_REDIS_REST_URL is set,
// with a clean in-memory TTL store fallback for local development or single-instance tests.
// Never records personal identifiers, Microsoft IDs, usernames, or IP addresses.

const LIVE_WINDOW_MS = 5 * 60 * 1000;
const DAY_WINDOW_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const RATE_WINDOW_MS = 60 * 1000;
const GET_CACHE_MS = 10 * 1000;
const MAX_POST_PER_ID = 15;
const MAX_POST_PER_IP = 30;
const MAX_GET_PER_IP = 60;
const MAX_BODY_BYTES = 1024;
const ALLOWED_CHIPS = new Set(['M1', 'M2', 'M3', 'M4', 'M5', 'Apple Silicon']);
const VERSION_RE = /^v?\d{1,3}\.\d{1,3}(?:\.\d{1,3})?(?:-[a-zA-Z0-9.]{1,12})?$/;

const mem = globalThis.__silicaStats || (globalThis.__silicaStats = {
  salt: crypto.randomBytes(16),
  sessions: new Map(),   // id -> { ts, playing, backend, version, chip }
  buckets: new Map(),    // hourEpoch -> { online: Set, playing: Set }
  rates: new Map(),      // ephemeral key -> { resetAt, count }
  getCache: null,        // { expiresAt, payload, persisted }
});

function cleanId(raw) {
  if (typeof raw !== 'string') return null;
  const s = raw.trim();
  return /^[a-zA-Z0-9_-]{8,64}$/.test(s) ? s : null;
}

function cleanChip(raw) {
  if (typeof raw !== 'string') return null;
  const s = raw.trim();
  return ALLOWED_CHIPS.has(s) ? s : null;
}

function cleanVersion(raw) {
  if (typeof raw !== 'string') return null;
  const s = raw.trim();
  if (s === 'dev' || VERSION_RE.test(s)) return s;
  return null;
}

function hashedClientKey(req) {
  const fwd = String(req.headers['x-forwarded-for'] || req.socket?.remoteAddress || 'unknown').split(',')[0].trim();
  return crypto.createHmac('sha256', mem.salt).update(fwd).digest('base64url').slice(0, 16);
}

function allowRate(key, limit, now) {
  if (mem.rates.size > 2000) {
    for (const [k, v] of mem.rates.entries()) {
      if (v.resetAt <= now) mem.rates.delete(k);
    }
    if (mem.rates.size > 2000) mem.rates.clear();
  }
  const cur = mem.rates.get(key);
  if (!cur || cur.resetAt <= now) {
    mem.rates.set(key, { resetAt: now + RATE_WINDOW_MS, count: 1 });
    return true;
  }
  if (cur.count >= limit) return false;
  cur.count++;
  return true;
}

function redisConfig() {
  const url = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL;
  const token = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN;
  return url && token ? { url: url.replace(/\/$/, ''), token } : null;
}

async function redisPipeline(cfg, commands) {
  const res = await fetch(`${cfg.url}/pipeline`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${cfg.token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(commands),
  });
  if (!res.ok) throw new Error(`Redis error ${res.status}`);
  return res.json();
}

function parseHashPairs(arr) {
  const out = {};
  if (!Array.isArray(arr)) return out;
  for (let i = 0; i < arr.length; i += 2) {
    if (typeof arr[i] === 'string' && arr[i + 1] != null) {
      out[arr[i]] = String(arr[i + 1]);
    }
  }
  return out;
}

module.exports = async (req, res) => {
  if (req.method === 'GET' || req.method === 'OPTIONS') {
    res.setHeader('Access-Control-Allow-Origin', '*');
  }
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    res.setHeader('Cache-Control', 'no-store');
    return res.status(204).end();
  }
  if (req.method !== 'GET' && req.method !== 'POST') {
    res.setHeader('Cache-Control', 'no-store');
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const now = Date.now();
  const ipKey = hashedClientKey(req);
  const cfg = redisConfig();

  if (req.method === 'GET') {
    if (!allowRate(`g:${ipKey}`, MAX_GET_PER_IP, now)) {
      res.setHeader('Cache-Control', 'no-store');
      res.setHeader('Retry-After', '60');
      return res.status(429).json({ error: 'Rate limit exceeded' });
    }
    res.setHeader('Cache-Control', 'public, s-maxage=15, stale-while-revalidate=30');
    if (mem.getCache && mem.getCache.expiresAt > now && mem.getCache.persisted === Boolean(cfg)) {
      return res.status(200).json(mem.getCache.payload);
    }
  } else {
    res.setHeader('Cache-Control', 'no-store');
    const len = Number(req.headers['content-length'] || 0);
    if (len > MAX_BODY_BYTES) return res.status(413).json({ ok: false });
    if (!allowRate(`p:${ipKey}`, MAX_POST_PER_IP, now)) {
      res.setHeader('Retry-After', '60');
      return res.status(429).json({ ok: false });
    }
  }

  const liveCutoff = now - LIVE_WINDOW_MS;
  const dayCutoff = now - DAY_WINDOW_MS;
  const curHour = Math.floor(now / HOUR_MS);

  let body = {};
  if (req.method === 'POST') {
    if (typeof req.body === 'string') {
      if (req.body.length > MAX_BODY_BYTES) return res.status(413).json({ ok: false });
      try { body = JSON.parse(req.body || '{}'); } catch { return res.status(400).json({ ok: false }); }
    } else if (req.body && typeof req.body === 'object') {
      body = req.body;
    }
  }
  const id = req.method === 'POST' ? cleanId(body.id) : null;
  if (req.method === 'POST') {
    if (!id) return res.status(400).json({ ok: false, persisted: Boolean(cfg) });
    if (!allowRate(`id:${id}`, MAX_POST_PER_ID, now)) {
      res.setHeader('Retry-After', '60');
      return res.status(429).json({ ok: false, persisted: Boolean(cfg) });
    }
  }
  const playing = body.state === 'playing';
  const backend = body.backend === 'gl' ? 'gl' : 'vulkan';
  const chip = cleanChip(body.chip);
  const version = cleanVersion(body.version);

  try {
    if (cfg) {
      const cmds = [];
      if (id) {
        cmds.push(['ZADD', 'silica:online', String(now), id]);
        if (playing) cmds.push(['ZADD', 'silica:playing', String(now), id]);
        else cmds.push(['ZREM', 'silica:playing', id]);

        cmds.push(['ZADD', 'silica:dau', String(now), id]);
        cmds.push(['HSET', `silica:meta:${id}`, 'b', backend, ...(chip ? ['c', chip] : []), ...(version ? ['v', version] : [])]);
        cmds.push(['EXPIRE', `silica:meta:${id}`, '86400']);

        const hKeyOn = `silica:h:${curHour}:on`;
        const hKeyPl = `silica:h:${curHour}:pl`;
        cmds.push(['SADD', hKeyOn, id]);
        cmds.push(['EXPIRE', hKeyOn, '90000']);
        if (playing) {
          cmds.push(['SADD', hKeyPl, id]);
          cmds.push(['EXPIRE', hKeyPl, '90000']);
        }
      }

      if (req.method === 'POST') {
        if (cmds.length > 0) {
          await redisPipeline(cfg, cmds);
          mem.getCache = null;
        }
        return res.status(200).json({ ok: Boolean(id), persisted: true });
      }

      cmds.push(['ZREMRANGEBYSCORE', 'silica:online', '-inf', String(liveCutoff)]);
      cmds.push(['ZREMRANGEBYSCORE', 'silica:playing', '-inf', String(liveCutoff)]);
      cmds.push(['ZREMRANGEBYSCORE', 'silica:dau', '-inf', String(dayCutoff)]);

      const resStart = cmds.length;
      cmds.push(['ZCARD', 'silica:online']);
      cmds.push(['ZCARD', 'silica:playing']);
      cmds.push(['ZCARD', 'silica:dau']);
      cmds.push(['ZRANGE', 'silica:dau', '0', '200']);
      for (let h = curHour - 23; h <= curHour; h++) {
        cmds.push(['SCARD', `silica:h:${h}:on`]);
        cmds.push(['SCARD', `silica:h:${h}:pl`]);
      }

      const out = await redisPipeline(cfg, cmds);
      const online = Number(out[resStart]?.result || 0);
      const playingCnt = Number(out[resStart + 1]?.result || 0);
      const dau = Number(out[resStart + 2]?.result || 0);
      const dauIds = Array.isArray(out[resStart + 3]?.result) ? out[resStart + 3].result : [];

      const history = [];
      for (let i = 0; i < 24; i++) {
        const h = curHour - 23 + i;
        const on = Number(out[resStart + 4 + i * 2]?.result || 0);
        const pl = Number(out[resStart + 4 + i * 2 + 1]?.result || 0);
        history.push({ hour: h * HOUR_MS, online: on, playing: pl });
      }

      const backends = { vulkan: 0, gl: 0 };
      const chips = {};
      const versions = {};
      if (dauIds.length > 0) {
        const metaCmds = dauIds.map(sid => ['HGETALL', `silica:meta:${sid}`]);
        const metaOut = await redisPipeline(cfg, metaCmds);
        for (const r of metaOut) {
          const kv = parseHashPairs(r?.result);
          if (kv.b === 'vulkan' || kv.b === 'gl') backends[kv.b]++;
          if (kv.c) chips[kv.c] = (chips[kv.c] || 0) + 1;
          if (kv.v) versions[kv.v] = (versions[kv.v] || 0) + 1;
        }
      }

      const payload = { online, playing: playingCnt, dau, persisted: true, history, backends, chips, versions };
      mem.getCache = { expiresAt: now + GET_CACHE_MS, payload, persisted: true };
      return res.status(200).json(payload);
    }

    // In-memory fallback (dev / unlinked Redis)
    if (id) {
      mem.sessions.set(id, { ts: now, playing, backend, chip, version });
      let bucket = mem.buckets.get(curHour);
      if (!bucket) { bucket = { online: new Set(), playing: new Set() }; mem.buckets.set(curHour, bucket); }
      bucket.online.add(id);
      if (playing) bucket.playing.add(id);
    }
    if (req.method === 'POST') {
      return res.status(200).json({ ok: Boolean(id), persisted: false });
    }

    let online = 0, playingCnt = 0, dau = 0;
    const backends = { vulkan: 0, gl: 0 };
    const chips = {};
    const versions = {};

    for (const [k, s] of mem.sessions.entries()) {
      if (s.ts < dayCutoff) { mem.sessions.delete(k); continue; }
      dau++;
      if (s.ts >= liveCutoff) {
        online++;
        if (s.playing) playingCnt++;
      }
      if (s.backend === 'vulkan' || s.backend === 'gl') backends[s.backend]++;
      if (s.chip) chips[s.chip] = (chips[s.chip] || 0) + 1;
      if (s.version) versions[s.version] = (versions[s.version] || 0) + 1;
    }

    for (const h of mem.buckets.keys()) if (h < curHour - 24) mem.buckets.delete(h);
    const history = [];
    for (let i = 0; i < 24; i++) {
      const h = curHour - 23 + i;
      const b = mem.buckets.get(h);
      history.push({ hour: h * HOUR_MS, online: b ? b.online.size : 0, playing: b ? b.playing.size : 0 });
    }

    const payload = { online, playing: playingCnt, dau, persisted: false, history, backends, chips, versions };
    mem.getCache = { expiresAt: now + GET_CACHE_MS, payload, persisted: false };
    return res.status(200).json(payload);
  } catch (err) {
    return res.status(200).json({ online: 0, playing: 0, dau: 0, persisted: false, history: [], backends: { vulkan: 0, gl: 0 }, chips: {}, versions: {} });
  }
};
