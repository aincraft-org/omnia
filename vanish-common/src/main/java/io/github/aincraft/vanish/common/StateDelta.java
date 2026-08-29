package io.github.aincraft.vanish.common;

import java.util.Objects;
import java.util.UUID;

/** A one-version change to one player's vanish state. */
public record StateDelta(long version, UUID playerId, boolean vanished) {
  public StateDelta {
    Objects.requireNonNull(playerId, "playerId");
  }
}
