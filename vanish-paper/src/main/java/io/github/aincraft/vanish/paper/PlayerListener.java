package io.github.aincraft.vanish.paper;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Applies the current vanish state as players enter, leave, or change worlds. */
public final class PlayerListener implements Listener {
  private final JavaPlugin plugin;
  private final VanishManager manager;
  private BukkitTask permissionTask;

  public PlayerListener(JavaPlugin plugin, VanishManager manager) {
    this.plugin = plugin;
    this.manager = manager;
  }

  /** Starts the main-thread permission fingerprint reconciliation loop. */
  public void start() {
    permissionTask =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, manager::reconcilePermissionFingerprints, 20L, 20L);
  }

  /** Stops the permission reconciliation loop during plugin disable. */
  public void stop() {
    if (permissionTask != null) {
      permissionTask.cancel();
      permissionTask = null;
    }
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    manager.reconcileViewer(player);
    manager.reconcileTarget(player);
    plugin.getServer().getScheduler().runTask(plugin, manager::reapplyAll);
  }

  @EventHandler
  public void onWorldChange(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    manager.reconcileViewer(player);
    manager.reconcileTarget(player);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    manager.removeViewer(playerId);
    manager.removeTarget(playerId);
  }
}
