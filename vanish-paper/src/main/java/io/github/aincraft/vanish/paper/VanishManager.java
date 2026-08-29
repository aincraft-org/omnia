package io.github.aincraft.vanish.paper;

import io.github.aincraft.vanish.common.StateDelta;
import io.github.aincraft.vanish.common.VanishState;
import io.github.aincraft.vanish.common.VersionedState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Maintains authoritative vanish state and applies Paper-managed visibility changes. */
public final class VanishManager {
  private final Plugin plugin;
  private final Supplier<? extends Collection<? extends Player>> onlinePlayers;
  private final VersionedState state = new VersionedState();
  private final Map<VisibilityPair, Boolean> appliedVisibility = new HashMap<>();
  private final Map<UUID, Boolean> permissionFingerprints = new HashMap<>();

  /** Creates a manager using the server's online-player view. */
  public VanishManager(JavaPlugin plugin) {
    this(plugin, () -> plugin.getServer().getOnlinePlayers());
  }

  /** Creates a manager with an injectable online-player source. */
  public VanishManager(
      JavaPlugin plugin, Supplier<? extends Collection<? extends Player>> onlinePlayers) {
    this((Plugin) plugin, onlinePlayers);
  }

  VanishManager(Plugin plugin, Supplier<? extends Collection<? extends Player>> onlinePlayers) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
  }

  /** Returns the current immutable state view. */
  public VanishState snapshot() {
    return state.snapshot();
  }

  /** Returns whether the state is ready and has no known version gap. */
  public boolean hasValidState() {
    return state.ready() && !state.needsSnapshot();
  }

  /** Marks the cached state as stale until a full snapshot is applied. */
  void markSnapshotNeeded() {
    state.markSnapshotNeeded();
  }

  /** Returns whether the authority state marks the player as vanished. */
  public boolean isVanished(UUID playerId) {
    return state.vanished().contains(playerId);
  }

  /** Applies an authoritative snapshot and reconciles visibility on the Paper thread. */
  public void applySnapshot(VanishState snapshot) {
    state.applySnapshot(snapshot);
    runOnMainThread(() -> reconcileAllNow(false));
  }

  /** Applies an authoritative contiguous delta and reconciles its target when online. */
  public boolean applyDelta(StateDelta delta) {
    boolean applied = state.applyDelta(delta);
    if (applied) {
      runOnMainThread(() -> reconcileTargetByIdNow(delta.playerId(), false));
    }
    return applied;
  }

  /** Reconciles every online target from the perspective of one viewer. */
  public void reconcileViewer(Player viewer) {
    runOnMainThread(() -> reconcileViewerNow(viewer, false));
  }

  /** Reconciles every online viewer's visibility of one target. */
  public void reconcileTarget(Player target) {
    runOnMainThread(() -> reconcileTargetNow(target, false));
  }

  /** Reconciles all online viewers and targets. */
  public void reconcileAll() {
    runOnMainThread(() -> reconcileAllNow(false));
  }

  /** Reapplies current visibility once, including a Paper visibility verification. */
  public void reapplyAll() {
    runOnMainThread(() -> reconcileAllNow(true));
  }

  /** Reconciles permission fingerprints and converges changed vanish.see visibility. */
  public void reconcilePermissionFingerprints() {
    runOnMainThread(() -> reconcileAllNow(false));
  }

  /** Removes all visibility and permission bookkeeping for a viewer that quit. */
  public void removeViewer(UUID viewerId) {
    permissionFingerprints.remove(viewerId);
    appliedVisibility.keySet().removeIf(pair -> pair.viewerId().equals(viewerId));
  }

  /** Removes all visibility bookkeeping for a target that quit. */
  public void removeTarget(UUID targetId) {
    appliedVisibility.keySet().removeIf(pair -> pair.targetId().equals(targetId));
  }

  private void reconcileViewerNow(Player requestedViewer, boolean force) {
    List<Player> players = onlinePlayersNow();
    Map<UUID, Player> byId = indexPlayers(players);
    Player viewer = byId.get(requestedViewer.getUniqueId());
    if (viewer == null) {
      return;
    }
    refreshPermissionFingerprint(viewer);
    for (Player target : players) {
      if (!viewer.getUniqueId().equals(target.getUniqueId())) {
        reconcilePair(viewer, target, force);
      }
    }
  }

  private void reconcileTargetNow(Player requestedTarget, boolean force) {
    List<Player> players = onlinePlayersNow();
    Map<UUID, Player> byId = indexPlayers(players);
    Player target = byId.get(requestedTarget.getUniqueId());
    if (target == null) {
      return;
    }
    for (Player viewer : players) {
      if (!viewer.getUniqueId().equals(target.getUniqueId())) {
        refreshPermissionFingerprint(viewer);
        reconcilePair(viewer, target, force);
      }
    }
  }

  private void reconcileTargetByIdNow(UUID targetId, boolean force) {
    List<Player> players = onlinePlayersNow();
    for (Player target : players) {
      if (target.getUniqueId().equals(targetId)) {
        reconcileTargetNow(target, force);
        return;
      }
    }
  }

  private void reconcileAllNow(boolean force) {
    List<Player> players = onlinePlayersNow();
    Map<UUID, Player> byId = indexPlayers(players);
    Set<UUID> onlineIds = byId.keySet();
    permissionFingerprints.keySet().removeIf(id -> !onlineIds.contains(id));
    appliedVisibility
        .keySet()
        .removeIf(
            pair -> !onlineIds.contains(pair.viewerId()) || !onlineIds.contains(pair.targetId()));

    for (Player viewer : players) {
      refreshPermissionFingerprint(viewer);
      for (Player target : players) {
        if (!viewer.getUniqueId().equals(target.getUniqueId())) {
          reconcilePair(viewer, target, force);
        }
      }
    }
  }

  private void reconcilePair(Player viewer, Player target, boolean force) {
    VisibilityPair pair = new VisibilityPair(viewer.getUniqueId(), target.getUniqueId());
    boolean desiredHidden =
        VisibilityPolicy.mustHide(
            isVanished(target.getUniqueId()), permissionFingerprints.get(viewer.getUniqueId()));
    Boolean applied = appliedVisibility.get(pair);
    if (!force && applied != null && applied == desiredHidden) {
      return;
    }

    boolean currentlyHidden = !viewer.canSee(target);
    if (currentlyHidden != desiredHidden) {
      if (desiredHidden) {
        viewer.hidePlayer(plugin, target);
      } else {
        viewer.showPlayer(plugin, target);
      }
      viewer.canSee(target);
    }
    appliedVisibility.put(pair, desiredHidden);
  }

  private void refreshPermissionFingerprint(Player viewer) {
    permissionFingerprints.put(viewer.getUniqueId(), viewer.hasPermission(Permissions.SEE));
  }

  private List<Player> onlinePlayersNow() {
    Collection<? extends Player> players = onlinePlayers.get();
    if (players == null || players.isEmpty()) {
      return List.of();
    }
    return new ArrayList<>(players);
  }

  private static Map<UUID, Player> indexPlayers(List<Player> players) {
    Map<UUID, Player> byId = new HashMap<>();
    for (Player player : players) {
      byId.put(player.getUniqueId(), player);
    }
    return byId;
  }

  private void runOnMainThread(Runnable task) {
    if (Bukkit.isPrimaryThread()) {
      task.run();
    } else {
      plugin.getServer().getScheduler().runTask(plugin, task);
    }
  }

  private record VisibilityPair(UUID viewerId, UUID targetId) {}
}
