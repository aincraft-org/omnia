# Contributing

Thanks for helping improve VanishNoPacket. Start with an issue for behavior changes, especially changes involving the proxy authority, Redis protocol, or player visibility.

## Development setup

This is a Gradle multi-module project (`vanish-common`, `vanish-paper`, and `vanish-velocity`). Use Java compatible with the checked-in build and run:

```bash
./gradlew clean check
./gradlew :vanish-paper:shadowJar :vanish-velocity:shadowJar
```

The two `shadowJar` outputs are the deployable artifacts. Do not use thin jars for deployment. For a local network, use only an isolated development-network runtime and never stop or reconfigure user-owned listeners.

## Design constraints

- Cross-backend state is proxy-authoritative and durable through Redis; each Paper backend enforces the state it receives.
- Keep clients connecting to Velocity, not directly to backend servers.
- Use public Paper/Velocity APIs. Do not add NMS, ProtocolLib, hand-crafted packet injection, or raw packet construction without an explicitly approved design. Paper hide/show and Velocity tab-list operations are client-visible updates and are not zero packets.
- FR-008 direct player-info packet correction is absent unless separately approved.
- Preserve strict state-file validation, atomic writes, snapshot/delta versioning, and fail-closed corrupt-state recovery.

## Pull requests

Explain user-visible behavior, affected modules, configuration changes, Redis/state compatibility, and rollback considerations. Update `README.md` and `content/docs/` when operator or player workflows change. Keep backend IDs and secrets out of examples unless they are clearly placeholders.

Run the quality commands above before requesting review. If live runtime testing is unavailable, record the exact blocker in `docs/` and label each untested scenario `BLOCKED`; do not present blocked evidence as a pass.
