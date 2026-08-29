package io.github.aincraft.vanish.common;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Maintains an immutable view of state while enforcing contiguous versions. */
public final class VersionedState {
  private VanishState state = new VanishState(0, Set.of());
  private boolean ready;
  private boolean needsSnapshot;

  /** Replaces the state and marks this store ready to consume deltas. */
  public synchronized void applySnapshot(VanishState snapshot) {
    state = Objects.requireNonNull(snapshot, "snapshot");
    ready = true;
    needsSnapshot = false;
  }

  /** Applies a contiguous delta, or records that a snapshot is needed for a gap. */
  public synchronized boolean applyDelta(StateDelta delta) {
    Objects.requireNonNull(delta, "delta");
    if (!ready) {
      needsSnapshot = true;
      return false;
    }

    long currentVersion = state.version();
    if (delta.version() <= currentVersion) {
      return false;
    }
    if (delta.version() != currentVersion + 1) {
      needsSnapshot = true;
      return false;
    }

    Set<UUID> updated = new LinkedHashSet<>(state.vanished());
    if (delta.vanished()) {
      updated.add(delta.playerId());
    } else {
      updated.remove(delta.playerId());
    }
    state = new VanishState(delta.version(), updated);
    needsSnapshot = false;
    return true;
  }

  /** Returns the current immutable state snapshot. */
  public synchronized VanishState snapshot() {
    return state;
  }

  public synchronized long version() {
    return state.version();
  }

  public synchronized Set<UUID> vanished() {
    return state.vanished();
  }

  public synchronized boolean ready() {
    return ready;
  }

  public synchronized boolean needsSnapshot() {
    return needsSnapshot;
  }
}
