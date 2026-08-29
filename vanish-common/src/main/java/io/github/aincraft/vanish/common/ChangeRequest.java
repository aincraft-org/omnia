package io.github.aincraft.vanish.common;

import java.util.Objects;
import java.util.UUID;

/** A request to change one player's vanish state. */
public record ChangeRequest(UUID requestId, UUID playerId, boolean vanished) {
  public ChangeRequest {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(playerId, "playerId");
  }
}
