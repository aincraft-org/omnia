package io.github.aincraft.vanish.common;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Maintains an immutable view of state while enforcing contiguous versions. */
public final class VersionedState {
  private VanishState state = new VanishState(0, Set.of());
  private boolean snapshotReady;
  private boolean snapshotNeeded;

  /** Replaces the state and marks this store ready to consume deltas. */
  public synchronized void applySnapshot(VanishState snapshot) {
    state = Objects.requireNonNull(snapshot, "snapshot");
    snapshotReady = true;
    snapshotNeeded = false;
  }

  /** Applies a contiguous delta, or records that a snapshot is needed for a gap. */
  public synchronized boolean applyDelta(StateDelta delta) {
    Objects.requireNonNull(delta, "delta");
    if (!snapshotReady) {
      snapshotNeeded = true;
      return false;
    }

    long currentVersion = state.version();
    if (delta.version() <= currentVersion) {
      return false;
    }
    if (delta.version() != currentVersion + 1) {
      snapshotNeeded = true;
      return false;
    }
    Set<UUID> updated = new LinkedHashSet<>(state.vanished());
    if (delta.vanished()) {
      updated.add(delta.playerId());
    } else {
      updated.remove(delta.playerId());
    }
    state = new VanishState(delta.version(), updated);
    snapshotNeeded = false;
    return true;
  }

  /** Returns the current immutable state snapshot. */
  public synchronized VanishState snapshot() {
    return state;
  }

  /** Returns the current state version. */
  public synchronized long version() {
    return state.version();
  }

  /** Returns the immutable set of vanished player IDs. */
  public synchronized Set<UUID> vanished() {
    return state.vanished();
  }

  /** Returns whether a valid snapshot has been applied. */
  public synchronized boolean ready() {
    return snapshotReady;
  }

  /** Returns whether a full snapshot is needed before applying deltas. */
  public synchronized boolean needsSnapshot() {
    return snapshotNeeded;
  }
}
