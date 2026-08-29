package io.github.aincraft.vanish.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aincraft.vanish.common.VanishState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class VanishManagerTest {
  private static final UUID SELF_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Test
  void doesNotApplyVisibilityToVanishedPlayerThemself() {
    PaperTestDoubles.FakePlayer self = new PaperTestDoubles.FakePlayer(SELF_ID, "Self");
    Server server = PaperTestDoubles.server(List.of(self));
    Plugin plugin = PaperTestDoubles.plugin(server);
    VanishManager manager = new VanishManager(plugin, () -> List.of(self.player()));

    manager.applySnapshot(new VanishState(1, Set.of(SELF_ID)));

    assertEquals(0, self.hideCalls());
    assertEquals(0, self.showCalls());
  }

  @Test
  void appliesHideOnceAndDiffsRepeatedReconciliation() {
    PaperTestDoubles.FakePlayer viewer = new PaperTestDoubles.FakePlayer(SELF_ID, "Viewer");
    PaperTestDoubles.FakePlayer target = new PaperTestDoubles.FakePlayer(TARGET_ID, "Target");
    Server server = PaperTestDoubles.server(List.of(viewer, target));
    Plugin plugin = PaperTestDoubles.plugin(server);
    VanishManager manager =
        new VanishManager(plugin, () -> List.of(viewer.player(), target.player()));

    manager.applySnapshot(new VanishState(1, Set.of(TARGET_ID)));
    manager.reconcileAll();
    manager.reconcileTarget(target.player());

    assertEquals(1, viewer.hideCalls());
    assertEquals(0, viewer.showCalls());
  }
}
