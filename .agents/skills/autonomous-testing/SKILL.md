---
name: autonomous-testing
description: Use when building an autonomous Minecraft bot in Rust with Azalea, automating in-game tasks, distinguishing automatable tasks from those requiring approval, listening to protocol packets, or validating that an action was fully completed without a human logging in. Triggers include Azalea bots, task automation, packet listeners, completion verification, run-paper bot deployment, and remote bot connections.
---

# Azalea Autonomous Bot

Build an autonomous Minecraft bot with [Azalea](https://github.com/azalea-rs/azalea) that accomplishes in-game tasks, distinguishes what is safely automatable, listens to protocol packets, and proves an action completed from server-observed state — so a human never needs to log in to verify.

Core principle: **report success only from server-observed postconditions, never from what the client sent or predicted.**

## Authorization boundary

Automate only servers and accounts the operator owns or is explicitly authorized to use. Do not bypass authentication, anti-cheat, permissions, rate limits, or server policies. Azalea's stated goal is not triggering anti-cheats; preserve that boundary.

## Deployment

- Local Paper server via `run-paper` (see the `project-setup` skill): connect to `localhost`.
- Remote server: connect to an explicit host/IP when configured.
- Refuse implicit remote connections or ambiguous addresses. Resolve the target address once at startup from configuration; never guess.

```rust
use azalea::prelude::*;

async fn main() -> eyre::Result<()> {
    let address = std::env::var("MC_SERVER").unwrap_or_else(|_| "localhost".into());
    let account = Account::offline("bot");
    ClientBuilder::new()
        .set_handler(handle)
        .start(account, address)
        .await?;
    Ok(())
}
```

## Task classification

Classify every requested task before executing it:

| Class | Definition | Examples |
|---|---|---|
| **Automatable** | Deterministic, observable, bounded, and reversible or explicitly authorized | Navigate to coordinates, inspect/transfer chest contents, gather, craft, smelt, farm, place/break blocks where permissions and world state are known |
| **Requires approval** | Destructive, irreversible, high-risk, ambiguous, or resource-sensitive | Breaking protected blocks, dropping items, spending scarce resources, actions with unknown world state |
| **Not automatable** | Depends on human judgment, hidden server state, unsupported mechanics, CAPTCHA-like challenges, or unverifiable outcomes | Anything whose success cannot be observed from the protocol |

When a task is ambiguous or unverifiable, fail closed: pause, return an explicit `blocked` or `uncertain` result with evidence, and require a new task or operator decision. Never guess.

## Execution model

1. Plan from declared preconditions (position, inventory, permissions, world state).
2. Execute one action at a time.
3. Correlate each action with observed packet/state changes.
4. Use bounded retries only for transient failures (transport, pathfinding); never retry past an unverified state.

## Observing completion from packets

Azalea exposes authoritative server updates through `Event::Packet(Arc<ClientboundGamePacket>)`. Match the underlying protocol packet:

```rust
use azalea::prelude::*;
use azalea::protocol::packets::game::ClientboundGamePacket;

async fn handle(bot: Client, event: Event, state: ()) -> eyre::Result<()> {
    match event {
        Event::Packet(packet) => match &*packet {
            ClientboundGamePacket::BlockUpdate(p) => {
                // Single block changed; check p.pos and p.block_state.
            }
            ClientboundGamePacket::SectionBlocksUpdate(p) => {
                // Several blocks changed in one packet.
            }
            ClientboundGamePacket::ContainerSetContent(p) => {
                // Entire container contents replaced.
            }
            ClientboundGamePacket::ContainerSetSlot(p) => {
                // One container/inventory slot changed.
            }
            ClientboundGamePacket::SetPlayerInventory(p) => {
                // One player-inventory slot updated.
            }
            _ => {}
        },
        _ => {}
    }
    Ok(())
}
```

For ECS-based code, use the lower-level `ReceiveGamePacketEvent` and match the same packet payload.

## Completion validators

There is no universal "action succeeded" event. Implement task-specific postcondition validators over the packet stream and synchronized client world/inventory state:

| Task | Action | Completion proof |
|---|---|---|
| Mine block | `Client::mine(pos)` | Target block changes to expected state; expected item appears or inventory delta observed |
| Place block | Interaction event | Target position becomes expected block state |
| Move | `goto(goal).await` | Position satisfies goal and stays stable for a confirmation window |
| Open chest | `open_container_at(pos)` | Container-open event/state exists with expected window/container ID |
| Move items | `ContainerClickEvent` | Server sends matching inventory/container updates and final slot contents match |
| Craft | Inventory/crafting interaction | Output/result slot and resulting inventory match expected postcondition |
| Smelt | Furnace interaction | Input/fuel/output slots and expected output state observed |
| Farm | Navigation + interaction | Expected crop/block state and inventory delta both confirmed |

Read synchronized state from the client:

```rust
let world = bot.world()?;
let block_state = world.read().get_block_state(pos); // Option<BlockState>

let inventory = bot.component::<azalea_entity::inventory::Inventory>()?;
let state_id = inventory.state_id; // changes when the server updates inventory
```

Never report success based only on outgoing packets, local prediction, chat text, or pathfinder completion.

## Failure policy

Return one of:

- `completed` — server-observed postcondition met.
- `blocked` — task requires approval or cannot be classified.
- `failed` — a definite error occurred.
- `uncertain` — postcondition could not be verified.

Include evidence: task ID, action, observed packets/events, before/after state, and timeout/retry data.

## Security hardening

The bot is a client and a potential target; harden it like any network-facing component:

- **Credentials**: never hardcode Microsoft accounts, tokens, or server passwords. Load them from environment variables or a secrets file outside the repository, and never commit them.
- **Input validation**: treat every task parameter as untrusted. Validate coordinates, item names, quantities, and commands against allowlists before acting. Never interpolate task input into shell commands or file paths.
- **Least privilege**: run the bot with the minimum account permissions the task requires. Do not grant operator/OP privileges to a bot account unless the task genuinely requires them.
- **Rate limiting**: respect server rate limits and plugin cooldowns. Do not spam actions; a bot that hammers the server triggers anti-cheat and disrupts other players.
- **Audit trail**: log every action with a task ID, timestamp, and outcome. If the bot is ever compromised or misbehaves, the audit trail is the only way to reconstruct what happened.
- **Fail closed**: on any error, uncertainty, or unexpected state, stop and report rather than continuing. Never guess past an unverified state.

## Testing

Test the bot against a local `run-paper` server before any remote deployment:

- **Unit tests**: test task classification, postcondition validators, and failure-policy logic in isolation with `cargo test`. These do not need a live server.
- **Integration tests**: run the bot against a local `run-paper` server and assert the server-observed postcondition for each task. Use a dedicated test world so bot actions cannot damage real progress.
- **Negative tests**: verify the bot reports `blocked` or `uncertain` for unverifiable, unauthorized, or ambiguous tasks — never `completed`.
- **Idempotency**: run the same task twice and confirm the second run reports `completed` without duplicating side effects (e.g. no double item transfer, no duplicate crafting).
- **Reconnect tests**: kill and restart the server while a task is in flight; confirm the bot reconnects and either completes or reports `uncertain` with evidence, never a false `completed`.

Run the full suite before every remote deployment, and keep the integration harness in the same repository as the bot so it stays reproducible.

## Load generation

The bot doubles as a repeatable load harness for performance work. A fleet of N bots running a seeded script produces identical peak load across runs — exactly what the `performance-optimization` skill's A/B test requires.

- Run N bots concurrently (one process per bot, or one process with N tasks) against the local `run-paper` server.
- Drive every bot from the same seeded script: same world, same actions, same timing. Deterministic load is the point; randomizing the seed changes the workload between trials.
- Keep the load script in the same repository as the bot so trials are reproducible.
- Respect the authorization and rate-limit rules from the Security hardening section — a load fleet is still a set of clients hitting a server you own.
- Confirm the fleet reaches the target player count and stays connected before starting a measurement window; a half-connected fleet measures a different workload.

## Verify

```bash
cargo build
cargo test
# Against a local run-paper server:
MC_SERVER=localhost cargo run
# Against a configured remote server:
MC_SERVER=play.example.com:25565 cargo run
```

Manually dispatch a task and confirm the bot reports `completed` only after the server-observed postcondition is met, and `blocked`/`uncertain` for anything it cannot verify.

## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| Report success after sending the action | Wait for server-observed postcondition | The client cannot know the server accepted the action |
| Trust pathfinder completion as task success | Verify the world/inventory state | Arriving is not completing the task |
| Trust chat text as confirmation | Verify protocol/state changes | Chat is not authoritative state |
| Retry indefinitely on unverified state | Fail closed with `uncertain` | Guessing past unverified state produces wrong or destructive outcomes |
| Automate without authorization | Restrict to owned/authorized servers and accounts | Bypassing anti-cheat, permissions, or rate limits is out of scope |
| Guess a remote address | Require explicit host/IP configuration | Implicit remote connections are unsafe |
| Treat every packet as authoritative for the task | Correlate packets with the specific action and postcondition | Unrelated updates are not evidence of completion |
| Hardcode bot credentials in the repository | Load from environment variables or a secrets file | Committed secrets leak to anyone with repo access |
| Trust task input without validation | Validate against allowlists before acting | Malicious input can inject commands or corrupt state |
| Run the bot with OP/operator privileges | Least privilege per task | A compromised bot with OP can destroy the server |
| Deploy to remote without local tests | Test against local run-paper first | Unverified bot behavior can damage a live server |
| Skip negative tests | Assert `blocked`/`uncertain` for unverifiable tasks | The bot must never report `completed` on a guess |
| No audit trail | Log every action with task ID and outcome | Without logs, misbehavior cannot be reconstructed |
| Randomizing load between trials | One seeded script, same world and timing | A/B comparisons need identical peak load (see performance-optimization) |
