# omnia

Agent skills for Paper/plugin development, consumed from `.agents/skills/`.

## Worktree convention (enforced)

Git does **not** default worktrees into the repo — the path must always be explicit.

- Create: `git worktree add .worktrees/<name> -b <branch> main`
- **Never** create worktrees as sibling directories under the parent of this repo.
  Worktrees MUST live inside `.worktrees/` (ignored via `.gitignore`).
- After merging: `git worktree remove .worktrees/<name>` then `git worktree prune`; delete the branch.
- `git worktree list` is the source of truth for active worktrees.

## Project guidance

- This repository builds three modules: `vanish-common`, `vanish-paper`, and `vanish-velocity`.
- Use `./gradlew clean check` for the complete quality gate. Deployable artifacts are `:vanish-paper:shadowJar` and `:vanish-velocity:shadowJar`; do not deploy thin `jar` outputs.
- Keep the product boundary intact: use Paper/Velocity public APIs, avoid NMS, ProtocolLib, hand-crafted packet injection, and raw packet construction. Paper hide/show remains client-visible and is not zero packets.
- Redis and the Velocity state file are shared-authority infrastructure. Every backend needs a distinct `backend-id`, and documentation must match the checked-in resource configs and manifests.
- Runtime claims must cite the dated records under `docs/`. A `BLOCKED` row is not a pass; never replace or reinterpret blocked smoke evidence.
- Consumer documentation belongs in `README.md` and `content/docs/`. Keep `content/docs/meta.json` navigation ordered and links pointed at real files.
