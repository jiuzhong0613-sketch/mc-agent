# Contributing

Thanks for helping. A few rules keep ports cheap:

1. **Protocol changes need a reason.** Adding fields is fine; renaming or
   removing requires a major version and a note in `docs/PROTOCOL.md`.
2. **Never block the client thread.** New actions must be tick-driven and must
   release keys in `cancel`.
3. **Client only.** Do not add server-side entry points.
4. **Config gates destructive actions.** Attack and chat stay opt-in.
5. **Describe the platform.** If you add a loader or version, update the README
   and `docs/PORTING.md`.

## Local build

```bash
./gradlew build        # JDK 17
./gradlew runClient    # needs a Minecraft account for multiplayer
```

CI is the source of truth for compilation; local Minecraft assets are optional.
