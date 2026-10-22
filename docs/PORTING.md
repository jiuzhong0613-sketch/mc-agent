# Porting to another loader or Minecraft version

The goal is that a port touches **two files** (`McAgentMod`, plus mapping
fixes in `Actions~/`Observation`) and nothing else.

## 1. Add a module

Keep the Forge 1.20.1 module untouched and add a sibling module, for example
`platforms/fabric-1.20.1`, that depends on the shared sources. Do not fork
the protocol, queue or config.

If you plan to support more than two versions, use a multi-version Gradle
plugin (Stonecutter) instead of copying source trees. Only the adapter layer
should differ per version.

## 2. Replace the entry point

Forge:

```java
@Mod("mcagent")
public final class McAgentMod { ... }
```

Fabric:

```java
public final class McAgentFabric implements ModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> AgentRuntime.get().tick());
        // register config, chat event
    }
}
```

The runtime, bridge and actions are loader agnostic; only event registration and
config plumbing change.

## 3. Fix mappings

Between Minecraft versions, expect changes in:

- `MultiPlayerGameMode` method names (destroy/use/attack),
- `LocalPlayer.chat` and chat packet signing,
- `ClientLevel.entitiesForRendering`,
- `SharedConstants.getCurrentVersion`,
- menu and slot access.

Keep those behind small helper methods so the diff stays local.

## 4. Keep the contract

- bump nothing in the protocol unless the JSON shape changes;
- add a capability string when you add behaviour;
- preserve the `/state` shape: agents must not need per-version branches.

## 5. Checklist

- [ ] `/health` reports the right MC version and capabilities
- [ ] `/state` works in singleplayer and on a multiplayer server
- [ ] timeout/cancel leaves keys released
- [ ] mod is client-only (no dedicated server crash)
- [ ] CI builds the new module
