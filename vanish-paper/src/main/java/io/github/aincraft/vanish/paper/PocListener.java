package io.github.aincraft.vanish.paper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Temporary API-only visibility POC. Remove this listener when the full vanish implementation lands.
 */
public final class PocListener implements Listener {
  private final JavaPlugin plugin;
  private final Set<UUID> vanishedTargets = new HashSet<>();

  public PocListener(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public int setVanished(Player target, boolean vanished) {
    if (vanished) {
      vanishedTargets.add(target.getUniqueId());
      int affected = reconcileAll("command-on");
      scheduleOneTickReapply();
      return affected;
    }
    vanishedTargets.remove(target.getUniqueId());
    int affected = 0;
    for (Player viewer : plugin.getServer().getOnlinePlayers()) {
      if (viewer.equals(target)) {
        continue;
      }
      viewer.showPlayer(plugin, target);
      boolean canSee = viewer.canSee(target);
      plugin.getLogger()
          .info(
              "[vanishpoc] command-off viewer="
                  + viewer.getUniqueId()
                  + " target="
                  + target.getUniqueId()
                  + " canSee="
                  + canSee);
      affected++;
    }
    scheduleOneTickReapply();
    return affected;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    reconcileAll("join");
    if (vanishedTargets.contains(event.getPlayer().getUniqueId())) {
      reconcileAll("target-server-arrival-join");
    }
    scheduleOneTickReapply();
  }

  @EventHandler
  public void onWorldChange(PlayerChangedWorldEvent event) {
    reconcileAll("world-change");
    scheduleOneTickReapply();
  }

  private void scheduleOneTickReapply() {
    plugin.getServer().getScheduler().runTask(plugin, () -> reconcileAll("one-tick-reapply"));
  }

  private int reconcileAll(String reason) {
    int applied = 0;
    for (Player target : plugin.getServer().getOnlinePlayers()) {
      if (!vanishedTargets.contains(target.getUniqueId())) {
        continue;
      }
      for (Player viewer : plugin.getServer().getOnlinePlayers()) {
        if (viewer.equals(target)) {
          continue;
        }
        if (viewer.hasPermission("vanish.see")) {
          viewer.showPlayer(plugin, target);
        } else {
          viewer.hidePlayer(plugin, target);
        }
        boolean canSee = viewer.canSee(target);
        plugin.getLogger()
            .info(
                "[vanishpoc] "
                    + reason
                    + " viewer="
                    + viewer.getUniqueId()
                    + " target="
                    + target.getUniqueId()
                    + " canSee="
                    + canSee);
        applied++;
      }
    }
    return applied;
  }
}
