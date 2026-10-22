# Architecture

```
Minecraft client (Forge)
  └─ McAgentMod            entry point, config, event bus
      ├─ AgentRuntime      tick driver, bridge lifecycle, event ring
      │   ├─ BridgeServer  HTTP/JSON, loopback only
      │   ├─ Observation   game state -> JSON
      │   └─ ActionQueue   one action at a time, tick aligned, timeouts
      │       └─ Actions   primitives (look/move/mine/place/...)
      └─ AgentConfig       permissions and limits
```

## Threading

Only the client thread touches the game. HTTP handlers submit a callable through
`ClientThread.call(...)`, which hops to the client thread and waits with a
timeout. Actions themselves are ticked from `TickEvent.ClientTickEvent` (END).

## Input without fake hardware

Movement does not synthesize OS key events. It sets `KeyMapping.setDown(...)`
for the relevant keys; vanilla `KeyboardInput` reads those mappings at the start
of the next tick, so physics, collision and sprint rules stay vanilla. Keys are
always released when an action finishes, times out, or is cancelled.

Interactions go through `MultiPlayerGameMode` (start/continue destroying,
`useItemOn`, `useItem`, `attack`), which is exactly what mouse clicks do.

## Why one action at a time

Concurrent actions would fight over keys and the destroy progress. Serializing
them also makes the agent loop trivial: submit, poll result, read new state.

## Boundaries that keep ports cheap

| Layer | Knows about Minecraft? |
|---|---|
| `bridge`, JSON schema | no |
| `ActionQueue` | only `Minecraft` for ticking |
| `Actions`, `Observation` | yes — this is the adapter surface |
| `McAgentMod` | loader entry point |

A Fabric port replaces `McAgentMod` and the imports in `Actions`/`Observation`.
A new Minecraft version usually changes a handful of method names. The protocol,
queue, config and JSON stay untouched.

## Known limits

- The client only knows loaded chunks; `walk_to` cannot plan through unloaded
  terrain, and there is no A* yet.
- No GUI automation (crafting tables, chests) beyond reading menu slots.
- Multiplayer servers may reject or punish automation; this is a client mod, not
  a server bypass.
