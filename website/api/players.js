// Anonymous live player counter backed by Upstash Redis / Vercel KV when configured,
// with an in-memory TTL map fallback for local/preview runs.
// Receives only { id: <random-uuid>, state: "launcher" | "playing" } — zero PII stored.

const WINDOW_MS = 5 * 60 * 1000;
const mem = globalThis.__silicaPlayers || (globalThis.__silicaPlayers = new Map());

function cleanId(raw) {
  if (typeof raw !== 'string') return null;
  const s = raw.trim().slice(0, 64);
  return /^[a-zA-Z0-9_-]{8,64}$/.test(s) ? s : null;
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

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();

  const now = Date.now();
  const cutoff = now - WINDOW_MS;
  const cfg = redisConfig();

  try {
    if (cfg) {
      const cmds = [];
      if (req.method === 'POST') {
        const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
        const id = cleanId(body.id);
        const playing = body.state === 'playing';
        if (id) {
          cmds.push(['ZADD', 'silica:online', String(now), id]);
          if (playing) cmds.push(['ZADD', 'silica:playing', String(now), id]);
          else cmds.push(['ZREM', 'silica:playing', id]);
        }
      }
      cmds.push(['ZREMRANGEBYSCORE', 'silica:online', '-inf', String(cutoff)]);
      cmds.push(['ZREMRANGEBYSCORE', 'silica:playing', '-inf', String(cutoff)]);
      cmds.push(['ZCARD', 'silica:online']);
      cmds.push(['ZCARD', 'silica:playing']);
      const out = await redisPipeline(cfg, cmds);
      const len = out.length;
      const online = Number(out[len - 2]?.result || 0);
      const playing = Number(out[len - 1]?.result || 0);
      return res.status(200).json({ online, playing, tracked: true });
    }

    if (req.method === 'POST') {
      const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
      const id = cleanId(body.id);
      if (id) mem.set(id, { ts: now, playing: body.state === 'playing' });
    }
    let online = 0, playing = 0;
    for (const [k, v] of mem.entries()) {
      if (v.ts < cutoff) mem.delete(k);
      else { online++; if (v.playing) playing++; }
    }
    return res.status(200).json({ online, playing, tracked: true });
  } catch (err) {
    return res.status(200).json({ online: 0, playing: 0, tracked: false });
  }
};
