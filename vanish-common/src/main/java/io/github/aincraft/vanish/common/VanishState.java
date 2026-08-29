package io.github.aincraft.vanish.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** An immutable versioned set of vanished player IDs. */
public record VanishState(long version, Set<UUID> vanished) {
  /** Validates IDs and stores them in deterministic order. */
  public VanishState {
    Objects.requireNonNull(vanished, "vanished");
    List<UUID> sorted = new ArrayList<>(vanished.size());
    for (UUID playerId : vanished) {
      sorted.add(Objects.requireNonNull(playerId, "vanished contains null UUID"));
    }
    sorted.sort(Comparator.comparing(UUID::toString));
    vanished = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
  }
}
