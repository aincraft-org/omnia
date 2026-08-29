package io.github.aincraft.vanish.common;

import java.util.Objects;
import java.util.UUID;

/** A request for the current authoritative vanish snapshot. */
public record SnapshotRequest(UUID requestId, String backendId) {
  /** Validates the request and backend identifiers. */
  public SnapshotRequest {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(backendId, "backendId");
  }
}
