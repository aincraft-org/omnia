package io.github.aincraft.vanish.common;

import java.util.Objects;
import java.util.UUID;

/** The authoritative result of a change request. */
public record ChangeAck(UUID requestId, boolean accepted, long version, String error) {
  public ChangeAck {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(error, "error");
  }
}
