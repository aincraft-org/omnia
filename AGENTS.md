# omnia

Agent skills for Paper/plugin development, consumed from `.agents/skills/`.

## Worktree convention (enforced)

Git does **not** default worktrees into the repo — the path must always be explicit.

- Create: `git worktree add .worktrees/<name> -b <branch> main`
- **Never** create worktrees as sibling directories under the parent of this repo.
  Worktrees MUST live inside `.worktrees/` (ignored via `.gitignore`).
- After merging: `git worktree remove .worktrees/<name>` then `git worktree prune`; delete the branch.
- `git worktree list` is the source of truth for active worktrees.