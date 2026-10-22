# Protocol: mc-agent/1

Transport: HTTP on `127.0.0.1`, JSON bodies, UTF-8.

If `bridge.token` is configured, every request must send
`X-McAgent-Token: <token>`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness, MC version, capabilities |
| GET | `/state` | full observation + last action result |
| GET | `/result` | last action result only |
| POST | `/act` | enqueue one action |
| POST | `/cancel` | cancel the running action and clear the queue |
| POST | `/query` | read-only probe, see below |

`/act` returns `202` when the action is queued, `409` when the queue is full,
`400` for a malformed or unknown action.

Actions are serialized. Poll `/result` (or read `lastResult` from `/state`)
until the returned `id` matches the queued action.

## Observation

```json
{
  "protocol": "mc-agent/1",
  "tick": 41233,
  "inGame": true,
  "paused": false,
  "screen": "InventoryScreen",
  "dimension": "minecraft:overworld",
  "player": {
    "name": "Steve", "uuid": "...",
    "pos": [120.5, 68.0, -34.2],
    "vel": [0.0, -0.078, 0.0],
    "yaw": 90.0, "pitch": 12.5,
    "onGround": true, "sneaking": false, "sprinting": false,
    "health": 20.0, "maxHealth": 20.0,
    "food": 18, "saturation": 5.0,
    "xpLevel": 3, "air": 300, "dead": false,
    "gameMode": "survival"
  },
  "inventory": {
    "selected": 0,
    "slots": [{"slot": 0, "item": "minecraft:stone_axe", "count": 1, "damage": 12}]
  },
  "lookingAt": {"kind": "block", "block": "minecraft:oak_log", "face": "up", "pos": [121, 68, -33]},
  "blocks": [{"x": 121, "y": 68, "z": -33, "id": "minecraft:oak_log"}],
  "entities": [{"id": 233, "type": "minecraft:zombie", "name": "Zombie",
                "pos": [125.0, 68.0, -30.0], "dist": 6.4, "health": 20.0}],
  "menu": {"open": false},
  "events": [{"seq": 7, "type": "chat", "text": "<Alex> hello"}],
  "capabilities": ["obs.player.v1", "act.move.v1"],
  "lastResult": {"id": "a12", "type": "walk_to", "ok": true, "ticks": 84}
}
```

`blocks` is capped (default 800, radius 5) and sorted by distance. `entities`
is capped at 48 within 32 blocks. Positions are rounded to millimetres.

## Actions

| `type` | Fields | Notes |
|---|---|---|
| `look` | `yaw`, `pitch` | absolute degrees |
| `look_at` | `x`, `y`, `z` | aims the camera at a point |
| `move` | `forward`, `strafe` (-1..1), `jump`, `sneak`, `sprint`, `ticks` | raw input for N ticks |
| `walk_to` | `x`, `y`, `z`, `tolerance`, `sprint` | naive steering, not pathfinding |
| `mine` | `x`, `y`, `z` | continuous digging until air |
| `place` | `x`, `y`, `z`, `face` | uses the held item |
| `use` | `hand` (`main`/`offhand`) | right click |
| `attack` | `entity` | entity id from observation |
| `select_slot` | `slot` (0..8) | hotbar |
| `chat` | `text` | disabled by default |
| `drop` | `all` | drops the held stack |
| `respawn` | — | only when dead |
| `wait` | `ticks` | idle |
| `stop` | — | cancel current + queue |

Every action returns:

```json
{"id": "a12", "type": "mine", "ok": true, "ticks": 47, "data": {"broken": true}}
```

Failures carry `"ok": false` and `"error"` (for example `out of reach`,
`timeout`, `stuck at distance 3.2`, `movement disabled by config`).

## Queries

`POST /query` with `{"name":"query_route","args":{"x":120,"y":68,"z":-34}}`.
Queries are read-only and never enter the action queue.

| Name | Args | Result |
|---|---|---|
| `query_block` | x,y,z | loaded, id, air, solid, fluid, hardness, light |
| `query_scan` | x,y,z,radius | type counts, nearest coordinates, solid samples |
| `query_entities` | radius | id, type, pos, dist, health, lineOfSight |
| `query_inventory` | — | selected + all non-empty slots |
| `query_raycast` | distance | kind, block/entity, pos, face, reach |
| `query_can_stand` | x,y,z | feetFree, headFree, floorSolid, water, lava, canStand |
| `query_surface` | x,z | surface y and block |
| `query_route` | x,y,z | verdict clear/blocked/water/lava/unloaded + first obstruction |
| `query_env` | — | time, weather, biome, light, difficulty |

## Versioning

`protocol` is a major version. Fields are only added within a major version.
Optional behaviour is advertised through `capabilities`; agents must ignore
unknown capabilities and handle missing ones.
