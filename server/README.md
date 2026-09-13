# Community spawn server

A tiny Cloudflare Worker with a D1 (SQLite) database that pools the spawn
tiles NPC Permadeath users observe. The plugin uploads tiles it learns and
downloads everyone else's for each NPC it needs, so totals fill in where the
wiki has no data. Runs comfortably inside the Cloudflare free tier.

## Deploy

1. Install Wrangler and log in: `npm install -g wrangler` then `wrangler login`.
2. Create the database: `wrangler d1 create npc-permadeath-spawns`. Paste the
   returned `database_id` into `wrangler.toml`.
3. Create the table: `wrangler d1 execute npc-permadeath-spawns --remote --file=schema.sql`.
4. Deploy: `wrangler deploy`. Note the `https://npc-permadeath-spawns.<account>.workers.dev` URL.
5. Put that URL in `NpcPermadeathConfig.DEFAULT_SYNC_URL` so every user gets it.

## API

| Method | Path | Body / result |
| --- | --- | --- |
| GET | `/npc/<name>` | `{ "name": "Goblin", "tiles": [{ "name", "id", "x", "y", "plane", "reports" }] }` |
| POST | `/report` | `{ "tiles": [{ "name", "id", "x", "y", "plane" }] }`, at most 200 per call, returns `{ "accepted": n }` |

Tiles are keyed by NPC name and position. Repeat reports increment
`reports`, which lets a client discount one-off mistakes later if needed.

## Abuse

No authentication, by design: the data is low value and the plugin only
sends coordinates. If it is ever abused, add a Cloudflare rate-limiting rule
on `/report` (Security > WAF > Rate limiting rules) or raise the `reports`
threshold clients accept.
