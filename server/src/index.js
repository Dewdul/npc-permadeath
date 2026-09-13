// Community spawn database for the NPC Permadeath RuneLite plugin.
//
//   GET  /npc/<name>   -> { name, tiles: [{ name, id, x, y, plane, reports }] }
//   POST /report       <- { tiles: [{ name, id, x, y, plane }] }   (max 200)
//
// Every tile is a spawn point a player saw an NPC respawn on. Tiles are keyed
// by NPC name and position; repeat reports bump a counter so one-off mistakes
// can be told apart from real spawns.

const MAX_TILES = 200;
const MAX_NAME = 60;
const MAX_COORD = 16383;
const MAX_ID = 100000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);

    if (request.method === "GET" && parts[0] === "npc" && parts.length === 2) {
      return getNpc(env, decodeURIComponent(parts[1]));
    }
    if (request.method === "POST" && parts[0] === "report" && parts.length === 1) {
      return report(env, request);
    }
    if (request.method === "GET" && parts.length === 0) {
      return json({ ok: true, service: "npc-permadeath-spawns" });
    }
    return json({ error: "not found" }, 404);
  },
};

async function getNpc(env, name) {
  if (!validName(name)) {
    return json({ error: "bad name" }, 400);
  }
  const { results } = await env.DB.prepare(
    "SELECT name, npc_id AS id, x, y, plane, reports FROM spawns WHERE name = ? ORDER BY x, y, plane",
  )
    .bind(name)
    .all();
  return json({ name, tiles: results }, 200, { "Cache-Control": "public, max-age=3600" });
}

async function report(env, request) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad json" }, 400);
  }
  const tiles = Array.isArray(body?.tiles) ? body.tiles.slice(0, MAX_TILES) : [];
  const now = Math.floor(Date.now() / 1000);
  const statements = [];
  const seen = new Set();
  for (const t of tiles) {
    if (!validTile(t)) {
      continue;
    }
    const key = `${t.name}|${t.x}|${t.y}|${t.plane}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    statements.push(
      env.DB.prepare(
        "INSERT INTO spawns (name, npc_id, x, y, plane, reports, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, 1, ?, ?) " +
          "ON CONFLICT (name, x, y, plane) DO UPDATE SET reports = reports + 1, last_seen = excluded.last_seen",
      ).bind(t.name, t.id, t.x, t.y, t.plane, now, now),
    );
  }
  if (statements.length > 0) {
    await env.DB.batch(statements);
  }
  return json({ accepted: statements.length });
}

function validName(name) {
  return typeof name === "string" && name.length > 0 && name.length <= MAX_NAME && !/[|\n\r]/.test(name);
}

function validTile(t) {
  return (
    t &&
    validName(t.name) &&
    Number.isInteger(t.id) && t.id >= 0 && t.id <= MAX_ID &&
    Number.isInteger(t.x) && t.x >= 0 && t.x <= MAX_COORD &&
    Number.isInteger(t.y) && t.y >= 0 && t.y <= MAX_COORD &&
    Number.isInteger(t.plane) && t.plane >= 0 && t.plane <= 3
  );
}

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...headers },
  });
}
