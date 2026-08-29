# SDD ledger — plan: docs/superpowers/plans/2026-08-29-vanish-no-packet.md

## Preflight plan scan

| Scope | Produces / consumes | Finding | Ruling |
|---|---|---|---|
| Task 1 → Task 2 | Build modules → common classes/tests | Task 1 must expose a Java 25 common module consumed by both platform modules; Task 2 owns the first common sources. | Scaffold only; no duplicate protocol code in platform modules. |
| Task 1 → Task 4 | Paper manifest/config → Paper plugin | Main class and permissions in Task 1 must match Task 4 wiring. | Keep manifest identifiers exact; Task 4 is the first implementation of behavior. |
| Task 1 → Task 6 | Velocity manifest/config → authority | Velocity entrypoint/config names must match Task 6. | Task 1 creates resources only; Task 6 owns loading/validation. |
| Task 1 → Task 8 | Gradle build → network tasks | The standalone project must expose deployable jars and remain compatible with the development-network composite. | Keep network integration isolated to Task 8; no harness changes in scaffold. |
| Task 2 → Task 5 | Message records/codec → Redis services | Both services depend on exact schema/type/channel names and version semantics. | Common module is the sole protocol owner; platform modules consume it. |
| Task 2 → Task 6 | `VanishState`/messages → proxy store | Store persistence and Redis publication must serialize the same canonical state. | Use common Gson codec and reject malformed schema. |
| Task 2 → Task 7 | UUID state → proxy filters | Proxy filters consume the same vanished UUID set as Paper. | No second proxy-specific state model. |
| Task 3 → Task 4 | POC result → default implementation | POC determines whether API-only remains valid; Task 4 must not add raw packets. | Execute POC before claiming tab behavior; FR-008 stays separate. |
| Task 4 → Task 5 | Paper manager/listeners → Redis reconciliation | Redis callbacks need a main-thread handoff into manager APIs. | Keep manager operations main-thread-only and transport asynchronous. |
| Task 5 → Task 6 | Change requests/acks → proxy authority | Paper sends desired-state requests; proxy returns acknowledgement/delta. | No optimistic backend mutation; proxy serializes requests. |
| Task 5 → Task 8 | Backend reconciliation → smoke matrix | Smoke cases require destination-arrival and offline/empty backend convergence. | Record applied versions/timing, not only visual observations. |
| Task 6 → Task 7 | Proxy store → tab/server masking | Surface masking reads authoritative vanished IDs and see exemptions. | Masking is derived state; it never mutates authority. |
| Task 6 → Task 8 | Proxy persistence/Redis → restart smoke | Proxy restart must reload JSON and republish durable snapshot. | Test valid restart and corrupt-state fail-closed separately. |
| Task 7 → Task 8 | Public Velocity surfaces → acceptance matrix | `/vservers`, tab entries, and direct built-in `/server` guard need runtime proof. | Never replace built-in `/server`; guard through `ServerPreConnectEvent`. |
| Task 8 → Task 9 | Smoke evidence → docs | Documentation must match observed commands, configs, timing, and recovery. | Write docs from recorded results; no unverified guarantees. |
| Task 3 self-consistency | POC files/API-only calls | Temporary POC must use only Paper visibility APIs and record all specified flows. | No NMS, ProtocolLib, or packet imports. |
| Task 4 self-consistency | Policy/manager/listeners/tests | Pure policy tests and main-thread manager methods cover lifecycle and permissions. | Keep permission decision separate from Bukkit mutation. |
| Task 5 self-consistency | Transport/service/tests | Fake transport must exercise ordering, gaps, reconnect, timeout, and join handoff. | Tests assert state/version behavior, not implementation text. |
| Task 6 self-consistency | Store/coordinator/tests | Corrupt file and failed write paths must preserve cached state and disable mutations. | Never publish an empty fallback for corrupt input. |
| Task 7 self-consistency | Filters/masker/command/tests | Public API masking and filtered command are distinct from backend entity hiding. | Both proxy and Paper enforcement are required. |
| Task 8 self-consistency | Network/results | Runtime scenarios cover every FR-004/006 destination case. | Use two clients and two backends through Velocity only. |
| Task 9 self-consistency | Docs/quality | Docs cannot claim packet-free or tab guarantees unsupported by Paper. | State raw-packet boundary and POC evidence explicitly. |
| Task 10 self-consistency | Conditional NMS branch | Task is unreachable unless POC fails and user approves constraint relaxation. | Do not dispatch or implement in default branch. |

## Rulings

- Ruling: “No packet” is interpreted as no direct packet injection/NMS/ProtocolLib in the default path, not zero packets on the wire — otherwise Paper-managed hide/show cannot satisfy the product requirement.
- Ruling: Cross-backend visibility requires proxy-wide authoritative state plus per-backend reconciliation; backend-only APIs are insufficient.
- Ruling: Built-in `/server` is not replaced because Velocity command aliases reject duplicates; direct access is controlled by `ServerPreConnectEvent` instead.
- Ruling: FR-008 remains approval-gated and is not part of the default implementation.

## Progress

BASE: `e1d72ed0c8a30fb2e01b719c9c9ef68f32bdb87a`

Task 1: fix round 1/5 (2 Important findings addressed; commits 144aac2..91be058)
Task 1: complete (commits e1d72ed..91be058, review clean after fix round 1)
Task 2: complete (commit 721c7a4, review clean)
Task 3: code complete (commit 601d568; review clean) but runtime gate blocked by existing user-owned ports 30066/30067/30068; no PASS claims.
Ruling: Continue implementing the default API-only path because the user explicitly requested development; treat the POC gate as outstanding and block final acceptance until an isolated two-client network run is available. Do not implement FR-008 without explicit approval.
Task 4: fix round 1/5 (coverage finding addressed; commits 04191c4..21c0fee)
Task 4: complete (commits 04191c4..21c0fee, review clean after fix round 1)
Task 5: fix round 1/5 dispatched for 8 review findings (pre-login gate, readiness, freshness, gap healing, backoff, cached join, snapshot request, publication ordering)
Task 5: fix round 2/5 dispatched for Redis resource cleanup regression (subscriber executor, operation executor, Jedis pool)
Task 5: fix round 2/5 (resource lifecycle finding addressed; commit 93bc6af)
Task 5: complete (commits 728c1db..93bc6af, review clean after fix rounds 1-2)
Task 6: fix round 1/5 dispatched for reconnect repair, shutdown race, version overflow, atomic durability, and blank state-file validation
Task 6: fix round 1/5 (5 findings addressed; commit 91eab17)
Task 6: complete (commits dd20b6c..91eab17, review clean after fix round 1)
Task 7: fix round 1/5 dispatched for re-added entry remasking, configured see-UUID wiring, and disconnected-target cleanup
Task 7: fix round 2/5 dispatched for shipped `see-uuids: false` compatibility and disconnected-viewer map cleanup
Task 7: fix round 2/5 (2 regressions addressed; commit 35d6fa3)
Task 7: complete (commits dcb4189..35d6fa3, review clean after fix rounds 1-2)
Task 8: fix round 1/5 dispatched for shadow-jar deployment, proxy runtime plugin path, and reproducible preflight evidence
Task 8: fix round 1/5 (3 findings addressed; commit e639cf5)
Task 8: fix round 2/5 dispatched for Velocity ProcessResources import and fail-closed preflight evidence
Task 8: fix round 2/5 (2 regressions addressed; commit 4cf76ca)
Task 8: complete (commits 24751b8..4cf76ca, review clean after fix rounds 1-2; runtime matrix remains BLOCKED by user-owned ports/harness limitation)
Task 9: fix round 1/5 dispatched for state-file-derived backup documentation and accurate Velocity reconnect retry wording
Task 9: fix round 1/5 dispatched for state-file-derived backup documentation and accurate Velocity reconnect retry wording
Task 9: fix round 1/5 (2 findings addressed; commit c99bfc4)
Task 9: fix round 2/5 dispatched for backup-preservation failure wording
Task 9: fix round 2/5 (backup-preservation failure wording addressed; commit 6b68ade)
Task 9: complete (commits 8ac7662..6b68ade, review clean after fix rounds 1-2)
Final review: FAIL — four findings recorded in final-review-fix-brief.md: pending Paper gap readiness; Velocity startup stale snapshot race; failed Redis publication stranded state; active subscription shutdown leak.
Final review fix: complete (commit 4021130, scoped re-review clean; four findings addressed)
Final acceptance baseline: code/build/docs checks pass; initial live smoke was blocked by user-owned listeners and shared harness lobby-port limitation; FR-008 remains unimplemented and approval-gated.

Root-cause investigation: an isolated Redis restart reproduced a failed first durable-snapshot write in `RedisVelocityService.onRedisConnected()`; the pre-fix callback logged the failure and had no retry path. The Paper/Velocity code path and logs identified the missing reconnect retry as the cause.
Reconnect repair fix: complete (commit 5a06e0d); a bounded delayed retry and shutdown guard were added, with a regression test observed failing before the fix and passing after it.
Live runtime follow-up: complete for reachable API/tab/routing evidence. Fixed-artifact alternate-port runs proved Velocity login and `/server` routing, Paper-managed tab masking/restoration, local Paper entity hide/show for a non-see viewer, cross-backend arrival, Redis snapshot repair, configured see exemption/revocation, proxy state-file reload, `/vservers` filtering, the built-in destination guard, and empty/offline-backend reconciliation. The required shared-port matrix remains blocked by user-owned listeners and the harness lobby-port gate; no-flicker, one-Paper-tick cross-backend, and zero-wire-packet claims remain unproven. FR-008 remains unimplemented and approval-gated.
