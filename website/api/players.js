// Anonymous live player & runtime statistics endpoint.
// Uses Upstash Redis / Vercel KV when KV_REST_API_URL or UPSTASH_REDIS_REST_URL is set,
// with a clean in-memory TTL store fallback for local development or single-instance tests.
// Never records personal identifiers, Microsoft IDs, usernames, or IP addresses.

const LIVE_WINDOW_MS = 5 * 60 * 1000;
const DAY_WINDOW_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;

const mem = globalThis.__silicaStats || (globalThis.__silicaStats = {
  sessions: new Map(),   // id -> { ts, playing, backend, version, chip }
  buckets: new Map(),    // hourEpoch -> { online: Set, playing: Set }
});

function cleanId(raw) {
  if (typeof raw !== 'string') return null;
  const s = raw.trim().slice(0, 64);
  return /^[a-zA-Z0-9_-]{8,64}$/.test(s) ? s : null;
}

function cleanTag(raw, allowed, maxLen = 24) {
  if (typeof raw !== 'string') return null;
  const s = raw.trim().slice(0, maxLen);
  if (!s) return null;
  if (allowed && !allowed.has(s)) return null;
  return /^[a-zA-Z0-9._ -]{1,24}$/.test(s) ? s : null;
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

function parseZSetPairs(arr) {
  const out = {};
  if (!Array.isArray(arr)) return out;
  for (let i = 0; i < arr.length; i += 2) {
    out[arr[i]] = Number(arr[i + 1] || 0);
  }
  return out;
}

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();

  const now = Date.now();
  const liveCutoff = now - LIVE_WINDOW_MS;
  const dayCutoff = now - DAY_WINDOW_MS;
  const curHour = Math.floor(now / HOUR_MS);
  const cfg = redisConfig();

  const body = req.method === 'POST'
    ? (typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {}))
    : {};
  const id = req.method === 'POST' ? cleanId(body.id) : null;
  const playing = body.state === 'playing';
  const backend = cleanTag(body.backend, new Set(['vulkan', 'gl'])) || 'vulkan';
  const chip = cleanTag(body.chip, null, 20);
  const version = cleanTag(body.version, null, 20);

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
          const kv = parseZSetPairs(r?.result);
          if (kv.b === 'vulkan' || kv.b === 'gl') backends[kv.b]++;
          if (kv.c) chips[kv.c] = (chips[kv.c] || 0) + 1;
          if (kv.v) versions[kv.v] = (versions[kv.v] || 0) + 1;
        }
      }

      return res.status(200).json({ online, playing: playingCnt, dau, persisted: true, history, backends, chips, versions });
    }

    // In-memory fallback (dev / unlinked Redis)
    if (id) {
      mem.sessions.set(id, { ts: now, playing, backend, chip, version });
      let bucket = mem.buckets.get(curHour);
      if (!bucket) { bucket = { online: new Set(), playing: new Set() }; mem.buckets.set(curHour, bucket); }
      bucket.online.add(id);
      if (playing) bucket.playing.add(id);
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

    return res.status(200).json({ online, playing: playingCnt, dau, persisted: false, history, backends, chips, versions });
  } catch (err) {
    return res.status(200).json({ online: 0, playing: 0, dau: 0, persisted: false, history: [], backends: { vulkan: 0, gl: 0 }, chips: {}, versions: {} });
  }
};
