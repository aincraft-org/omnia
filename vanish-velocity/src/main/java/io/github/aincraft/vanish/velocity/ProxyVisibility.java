package io.github.aincraft.vanish.velocity;

import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure policy helpers for proxy-wide vanish surfaces. */
public final class ProxyVisibility {
  private static final Comparator<String> BY_NAME =
      String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

  private ProxyVisibility() {}

  /** Returns true only when a destination has at least one connected player and all are vanished. */
  public static boolean isVanishedOnly(Collection<UUID> connected, Set<UUID> vanished) {
    Objects.requireNonNull(connected, "connected");
    Objects.requireNonNull(vanished, "vanished");
    return !connected.isEmpty() && connected.stream().allMatch(vanished::contains);
  }

  /** Returns whether a viewer is configured to see vanished players and destinations. */
  public static boolean canSeeVanished(Player viewer, Set<UUID> configuredSeeUuids) {
    Objects.requireNonNull(configuredSeeUuids, "configuredSeeUuids");
    return viewer != null && configuredSeeUuids.contains(viewer.getUniqueId());
  }

  /** Filters destination names and returns them in stable, case-insensitive order. */
  public static List<String> visibleDestinationNames(
      Map<String, ? extends Collection<UUID>> destinations,
      Set<UUID> vanished,
      boolean canSeeVanished) {
    Objects.requireNonNull(destinations, "destinations");
    Objects.requireNonNull(vanished, "vanished");
    List<String> visible = new ArrayList<>();
    for (Map.Entry<String, ? extends Collection<UUID>> destination : destinations.entrySet()) {
      String name = Objects.requireNonNull(destination.getKey(), "destination name");
      Collection<UUID> connected =
          Objects.requireNonNull(destination.getValue(), "connected players for " + name);
      if (canSeeVanished || !isVanishedOnly(connected, vanished)) {
        visible.add(name);
      }
    }
    visible.sort(BY_NAME);
    return List.copyOf(visible);
  }
}
