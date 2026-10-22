# MC Agent

**Give an AI a body in Minecraft — no screenshots, no computer vision.**

MC Agent is a client-side Minecraft mod that exposes the game in two directions:

- **state out**: a compact, stable JSON snapshot of what the player can perceive
  (position, inventory, nearby blocks and entities, chat events);
- **actions in**: a semantic action API (look, move, walk_to, mine, place, use,
  attack, chat) executed on the client thread, one action at a time.

Any program can drive it: an LLM tool-calling loop, a script, a reinforcement
learning harness, or a test suite. The mod does not ship an LLM and does not
need one.

## Why not vision

Pixel-based agents are slow, expensive and brittle. Minecraft already exposes
its entire world model to the client. MC Agent taps that model directly, so a
decision costs a few hundred tokens instead of an image, and an action is a
deterministic game call instead of a mouse movement guess.

## Status

Early. The first platform is **Minecraft 1.20.1 + Forge 47.x**, client side
only. Singleplayer and multiplayer both work, because everything happens in
your own client.

Planned: Fabric, 1.21.x, a skill layer (pathfinding, crafting), and an optional
reference agent. See [docs/PORTING.md](docs/PORTING.md) for how new platforms
plug in.

## Install

1. Install Minecraft 1.20.1 with Forge 47.x.
2. Drop `mcagent-0.1.0.jar` into `.minecraft/mods`.
3. Launch the game and enter a world.
4. The bridge starts on `http://127.0.0.1:25580`.

Config lives in `config/mcagent-client.toml` (created on first launch, edit
while the game is closed).

```toml
[bridge]
    enabled = true
    port = 25580
    token = ""          # optional; if set, send X-McAgent-Token

[permissions]
    allowMove = true
    allowBreak = true
    allowPlace = true
    allowAttack = false
    allowChat = false
```

## Try it

```bash
curl -s http://127.0.0.1:25580/health
curl -s http://127.0.0.1:25580/state | jq '.player'
curl -s -X POST http://127.0.0.1:25580/act \
  -H 'Content-Type: application/json' \
  -d '{"type":"walk_to","x":120,"y":68,"z":-34}'
curl -s http://127.0.0.1:25580/result
```

Full protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Design rules

1. **Client only.** The mod never loads on a dedicated server.
2. **Loopback only.** The bridge binds `127.0.0.1`; it is not a remote control.
3. **Tick aligned.** Actions run one per tick from the client thread, with
   timeouts; no blocking the render thread.
4. **Capabilities over versions.** Every response advertises what this build can
   do, so agents degrade instead of breaking.
5. **Small core, thin platforms.** Game-specific code is isolated behind a tiny
   adapter surface.

## Fair play

Automation may violate the rules of public servers. Use this on your own worlds
or where the server owner allows it. Attack and chat are disabled by default for
the same reason.

## Build

```bash
./gradlew build
```

Requires JDK 17. CI builds on GitHub Actions; no local Minecraft install is
needed to compile.

## License

MIT.
