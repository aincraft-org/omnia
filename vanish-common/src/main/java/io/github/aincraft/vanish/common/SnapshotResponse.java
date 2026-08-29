package io.github.aincraft.vanish.common;

import java.util.Objects;
import java.util.UUID;

/** A response containing the authoritative vanish snapshot for a backend. */
public record SnapshotResponse(UUID requestId, String backendId, VanishState state) {
  public SnapshotResponse {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(backendId, "backendId");
    Objects.requireNonNull(state, "state");
  }
}
