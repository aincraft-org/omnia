package io.github.aincraft.vanish.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UseProperClassLoader")
class ProxyVisibilityTest {
  private static final UUID VISIBLE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID VANISHED = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Test
  void emptyDestinationIsNotVanishedOnly() {
    assertFalse(ProxyVisibility.isVanishedOnly(List.of(), Set.of(VANISHED)));
  }

  @Test
  void mixedDestinationRemainsVisible() {
    assertFalse(ProxyVisibility.isVanishedOnly(List.of(VISIBLE, VANISHED), Set.of(VANISHED)));
  }

  @Test
  void nonEmptyDestinationOfOnlyVanishedPlayersIsMasked() {
    assertTrue(ProxyVisibility.isVanishedOnly(List.of(VANISHED), Set.of(VANISHED)));
  }

  @Test
  void configuredViewerIsExemptFromVanishedFiltering() {
    Player viewer =
        (Player)
            Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (ignored, method, arguments) ->
                    method.getName().equals("getUniqueId") ? VISIBLE : null);

    assertTrue(ProxyVisibility.canSeeVanished(viewer, Set.of(VISIBLE)));
  }

  @Test
  void visibleDestinationNamesAreSortedDeterministically() {
    Map<String, List<UUID>> destinations = new LinkedHashMap<>();
    destinations.put("zeta", List.of(VISIBLE));
    destinations.put("alpha", List.of());
    destinations.put("hidden", List.of(VANISHED));
    destinations.put("mixed", List.of(VISIBLE, VANISHED));

    assertEquals(
        List.of("alpha", "mixed", "zeta"),
        ProxyVisibility.visibleDestinationNames(destinations, Set.of(VANISHED), false));
    assertEquals(
        List.of("alpha", "hidden", "mixed", "zeta"),
        ProxyVisibility.visibleDestinationNames(destinations, Set.of(VANISHED), true));
  }
}
