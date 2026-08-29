## Summary

<!-- What changed, and which user/operator problem does it address? -->

## Deployment impact

- [ ] Paper jar changes were considered for every backend.
- [ ] Velocity jar changes were considered for the proxy.
- [ ] Redis keys/channels, state-file compatibility, and reconciliation behavior were reviewed.
- [ ] Configuration and manifests agree with the implementation.

## Product boundary

- [ ] No unapproved NMS, ProtocolLib, hand-crafted packet injection, or raw packet construction was added.
- [ ] I understand Paper hide/show and Velocity tab-list updates are client-visible updates, not zero packets.
- [ ] Any direct player-info packet correction is explicitly called out as approval-gated (FR-008 is absent by default).

## Verification

- [ ] `./gradlew clean check`
- [ ] `./gradlew :vanish-paper:shadowJar :vanish-velocity:shadowJar`
- [ ] I inspected the default source/dependencies for packet-level or NMS paths.
- [ ] Runtime claims cite `docs/` evidence and mark blocked scenarios as `BLOCKED`, not `PASS`.

## Notes

<!-- Include migration steps, risks, or known blockers. Redact credentials and forwarding secrets. -->
