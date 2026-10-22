# Active perception and memory

Two additions turn the agent from "one snapshot, one guess" into something that
behaves more like a player: it can ask the game questions, and it remembers what
it learned in this world.

## 1. Active perception

The state is deliberately partial: block scan radius 5, entities within 32,
loaded chunks only. `/state` now carries a `coverage` block so the model knows
the limits instead of assuming it sees everything.

When a fact is missing, the model calls a read-only query:

| Query | Answers |
|---|---|
| `query_block` | block id, solid/fluid, hardness, light |
| `query_scan` | type counts + nearest coordinates + solid samples (≤4096 blocks) |
| `query_entities` | radius, distance, health, line of sight |
| `query_inventory` | every non-empty slot with container index |
| `query_raycast` | first block or entity under the crosshair |
| `query_can_stand` | feet/head free, floor solid, water/lava |
| `query_surface` | surface height and block in a column |
| `query_route` | straight-line verdict: clear/blocked/water/lava/unloaded |
| `query_env` | time, weather, biome, light, difficulty |

Rules that keep this cheap:

- queries are read-only, so they never fight the action queue;
- results are cached for 5–20 ticks, keyed by query + arguments;
- at most `llm.maxQueriesPerStep` (default 6) per model step;
- query tools are available to the built-in agent and over `POST /query`.

### Framework-initiated probing

When an action fails, the framework asks a follow-up question automatically and
attaches it as `autoProbe`:

| Failed action | Probe |
|---|---|
| `walk_to` | `query_route` to the target |
| `mine` / `place` | `query_block` at the target |
| `attack` | `query_entities` within 16 |

So the model sees "failed: out of reach" plus the block's real state, instead of
guessing again.

## 2. Memory

Per-world JSON at `config/mcagent/memory/<world>.json` (0600 on POSIX):

| Tier | Content | Cap |
|---|---|---|
| facts | durable facts: resource locations, hazards, landmarks | 1200 |
| lessons | one-line rules learned from failures and `done` calls | 150 |
| skills | action names with success/failure counts | 80 |
| episodes | recent action outcomes with args and errors | 600 |

World key: singleplayer uses the level name; multiplayer uses the server IP.
Writes are debounced (≥5 s), atomic (tmp + move), and capped, so the file cannot
grow without bound.

Resolution of a fact is deliberately boring: text/tag match against the goal
tokens, plus confidence and recency. The top 6 facts, 4 lessons and 3 skills are
injected at start (~900 chars max). Mid-run, the model can call `recall`.

The model writes memory explicitly with `remember`:

- `kind=fact`: "oak_log cluster at 120,68,-33", "ravine at -40,12,300";
- `kind=lesson`: "walk_to across water fails; route around it first".

`done` accepts an optional `lesson` field, stored for the next run.

## 3. Learning loop

```
state -> plan -> tool call -> result
  ^                            |
  |                            v
memory <-- reflection <-- compare with expectation
```

- **episodes** record what was tried, with the error string;
- **lessons** are durable rules, deduplicated and confidence-weighted;
- **skills** accumulate success/failure counts, so repeated failures are visible;
- **recall** retrieves by keyword, not by embedding (no extra API cost).

This is retrieval + reflection, not model training. There is no fine-tuning, no
gradient update and no self-modifying code. Saying otherwise would be dishonest.

## 3.1 Executable skills

A successful run records its world actions. With 2–12 steps and no skill replay
involved, `done.skill` turns that sequence into a replayable macro:

```json
{
  "text": "chop_tree",
  "anchor": {"x": 120, "y": 68, "z": -33},
  "steps": [
    {"action": "look_at", "args": {"x": 120, "y": 69, "z": -33}},
    {"action": "mine", "args": {"x": 120, "y": 68, "z": -33}}
  ],
  "ok": 3, "fail": 1
}
```

- `anchor` is the first step with coordinates; `run_skill(name, x, y, z)` shifts every
  coordinate by the delta, so the same macro works at a new target;
- replay goes through the normal action queue, so permissions, reach checks,
  timeouts and key release all still apply;
- the first failed step aborts the replay and returns `failedStep`,
  `failedAction` and the per-step results;
- `list_skills` shows name, step count, ok/fail and whether it is executable;
- old text-only entries report `executable=false` and cannot be replayed.

## 3.2 Reflection

When a run ends in failure (error, step limit, exception), the framework makes
one extra request with no tools:

```json
{"lessons":[{"text":"walk_to through water fails; route around water first","tags":"walk_to,water"}]}
```

- controlled by `agent.reflectOnFailure` (default true);
- input: goal, failure reason, last 10 steps;
- at most two lessons, stored as `kind=lesson` with the goal as tags;
- duplicate lessons bump confidence instead of creating a second entry;
- network/auth errors are ignored silently; this never blocks the main loop;
- costs one extra request per failed run, not counted in `maxSteps`.

## 4. Human-like behaviour

| Knob | Default | Effect |
|---|---|---|
| `agent.turnSpeed` | 18°/tick | camera turns gradually instead of snapping 180° |
| `agent.stepDelayMs` | 250 ms | pause between decisions, less robotic |
| `agent.memory` | true | cross-session memory per world |

`look` and `look_at` now finish only when the camera is aligned, and their
timeout counts the rotation, so aiming takes a realistic number of ticks.

## 5. Honest limits

- Memory is text, not a map. Stale coordinates stay wrong until the model
  notices and overwrites them.
- Retrieval is keyword based; Chinese goals match by substring, not semantics.
- No vector store, no embeddings, no automatic fact extraction from raw scans.
- `query_scan` can see through walls within its radius, which is better than a
  player; `query_raycast` and line-of-sight checks are the realistic ones.
- Memory can be poisoned by a bad model output; entries are capped but not
  verified against the world on read.
