package io.github.aincraft.vanish.paper;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Applies the current vanish state as players enter, leave, or change worlds. */
@SuppressWarnings("PMD.NullAssignment")
public final class PlayerListener implements Listener {
  private final Plugin plugin;
  private final VanishManager manager;
  private final RedisPaperService redis;
  private BukkitTask permissionTask;

  /** Creates a listener without a Redis transport for local visibility use. */
  public PlayerListener(JavaPlugin plugin, VanishManager manager) {
    this((Plugin) plugin, manager, null);
  }

  /** Creates a listener with Redis-backed state reconciliation. */
  public PlayerListener(JavaPlugin plugin, VanishManager manager, RedisPaperService redis) {
    this((Plugin) plugin, manager, redis);
  }

  PlayerListener(Plugin plugin, VanishManager manager, RedisPaperService redis) {
    this.plugin = plugin;
    this.manager = manager;
    this.redis = redis;
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

  /** Keeps login closed when no valid authoritative state is available. */
  @EventHandler
  public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    if (redis == null) {
      return;
    }
    if (!redis.hasValidState()) {
      event.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          "Vanish state is temporarily unavailable; please try again.");
    }
    redis
        .reconcileForPreLogin()
        .whenComplete(
            (ignored, error) -> {
              if (error != null && !redis.hasValidState()) {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Vanish state is temporarily unavailable; please try again.");
              }
            });
  }

  /** Applies cached and freshly reconciled state when a player joins. */
  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (redis == null) {
      applyJoinVisibility(player);
      return;
    }
    boolean hadCachedState = redis.hasValidState();
    if (hadCachedState) {
      applyJoinVisibility(player);
    }
    long appliedVersion = manager.snapshot().version();
    redis
        .reconcileForJoin()
        .whenComplete(
            (state, error) -> {
              if (error == null && (!hadCachedState || state.version() > appliedVersion)) {
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(plugin, () -> applyJoinVisibility(player));
              }
            });
  }

  /** Reconciles viewer and target visibility after a world change. */
  @EventHandler
  public void onWorldChange(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    manager.reconcileViewer(player);
    manager.reconcileTarget(player);
  }

  /** Releases visibility bookkeeping when a player leaves. */
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    manager.removeViewer(playerId);
    manager.removeTarget(playerId);
  }

  private void applyJoinVisibility(Player player) {
    manager.reconcileViewer(player);
    manager.reconcileTarget(player);
    plugin.getServer().getScheduler().runTaskLater(plugin, manager::reapplyAll, 1L);
  }
}
