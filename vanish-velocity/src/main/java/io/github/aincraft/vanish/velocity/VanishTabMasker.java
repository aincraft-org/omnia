package io.github.aincraft.vanish.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Maintains per-viewer tab entries using only Velocity's public tab-list API. */
public final class VanishTabMasker {
  private static final int PERIODIC_BATCH_SIZE = 64;
  private static final Duration PERIOD = Duration.ofSeconds(1);

  private final ProxyServer proxy;
  private final Object stateLock = new Object();
  private final Map<UUID, Map<UUID, TabListEntry>> removedEntries = new HashMap<>();
  private volatile Set<UUID> vanished;
  private volatile Set<UUID> configuredSeeUuids;
  private ScheduledTask periodicTask;
  private int periodicCursor;

  public VanishTabMasker(ProxyServer proxy, Set<UUID> configuredSeeUuids) {
    this(proxy, Set.of(), configuredSeeUuids);
  }

  public VanishTabMasker(
      ProxyServer proxy, Set<UUID> vanished, Set<UUID> configuredSeeUuids) {
    this.proxy = Objects.requireNonNull(proxy, "proxy");
    this.vanished = immutableUuids(vanished, "vanished");
    this.configuredSeeUuids = immutableUuids(configuredSeeUuids, "configuredSeeUuids");
  }

  /** Replaces the read-only authority view and reconciles every currently online viewer. */
  public void onStateChanged(Set<UUID> vanished) {
    this.vanished = immutableUuids(vanished, "vanished");
    reconcileAll();
  }

  /** Reconciles one viewer without clearing entries owned by another plugin. */
  public void reconcile(Player viewer) {
    Objects.requireNonNull(viewer, "viewer");
    synchronized (stateLock) {
      reconcileLocked(viewer, vanished);
    }
  }

  /** Reconciles all online viewers after a state change or lifecycle event. */
  public void reconcileAll() {
    for (Player viewer : proxy.getAllPlayers()) {
      reconcile(viewer);
    }
  }
  /** Replaces configured viewers and restores entries for viewers whose visibility changed. */
  public void onConfiguredSeeUuidsChanged(Set<UUID> configuredSeeUuids) {
    this.configuredSeeUuids = immutableUuids(configuredSeeUuids, "configuredSeeUuids");
    reconcileAll();
  }

  /** Starts the bounded periodic reconciliation pass. */
  public void start(Object plugin) {
    Objects.requireNonNull(plugin, "plugin");
    synchronized (stateLock) {
      if (periodicTask != null) {
        return;
      }
      periodicTask =
          proxy
              .getScheduler()
              .buildTask(plugin, this::reconcileBounded)
              .delay(PERIOD)
              .repeat(PERIOD)
              .schedule();
    }
  }

  /** Stops the periodic reconciliation pass and releases its scheduler task. */
  public void close() {
    synchronized (stateLock) {
      if (periodicTask != null) {
        periodicTask.cancel();
        periodicTask = null;
      }
      periodicCursor = 0;
      removedEntries.clear();
    }
  }
  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    synchronized (stateLock) {
      removedEntries.remove(event.getPlayer().getUniqueId());
    }
    reconcile(event.getPlayer());
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    reconcileAll();
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    UUID disconnectedId = event.getPlayer().getUniqueId();
    synchronized (stateLock) {
      for (var iterator = removedEntries.entrySet().iterator(); iterator.hasNext(); ) {
        var viewerEntries = iterator.next();
        viewerEntries.getValue().remove(disconnectedId);
        if (viewerEntries.getValue().isEmpty()) {
          iterator.remove();
        }
      }
    }
  }
  @Subscribe
  public void onTabComplete(TabCompleteEvent event) {
    Player viewer = event.getPlayer();
    if (ProxyVisibility.canSeeVanished(viewer, configuredSeeUuids)
        || !isServerCompletion(event.getPartialMessage())) {
      return;
    }
    Set<UUID> currentVanished = vanished;
    event
        .getSuggestions()
        .removeIf(suggestion -> isHiddenDestination(suggestion, currentVanished));
  }

  private void reconcileBounded() {
    List<Player> players = new ArrayList<>(proxy.getAllPlayers());
    if (players.isEmpty()) {
      periodicCursor = 0;
      return;
    }
    int start = periodicCursor % players.size();
    int count = Math.min(PERIODIC_BATCH_SIZE, players.size());
    for (int offset = 0; offset < count; offset++) {
      reconcile(players.get((start + offset) % players.size()));
    }
    periodicCursor = (start + count) % players.size();
  }

  private void reconcileLocked(Player viewer, Set<UUID> currentVanished) {
    TabList tabList = viewer.getTabList();
    UUID viewerId = viewer.getUniqueId();
    Map<UUID, TabListEntry> removed = removedEntries.get(viewerId);
    boolean canSee = ProxyVisibility.canSeeVanished(viewer, configuredSeeUuids);
    if (canSee) {
      if (removed != null) {
        restoreEntries(tabList, removed);
        if (removed.isEmpty()) {
          removedEntries.remove(viewerId);
        }
      }
      return;
    }

    if (removed == null && !currentVanished.isEmpty()) {
      removed = new HashMap<>();
      removedEntries.put(viewerId, removed);
    }
    if (removed == null) {
      return;
    }

    for (UUID targetId : currentVanished) {
      if (!tabList.containsEntry(targetId)) {
        continue;
      }
      tabList
          .removeEntry(targetId)
          .ifPresent(entry -> removedEntries.get(viewerId).putIfAbsent(targetId, entry));
    }

    for (var iterator = removed.entrySet().iterator(); iterator.hasNext(); ) {
      var entry = iterator.next();
      UUID targetId = entry.getKey();
      if (currentVanished.contains(targetId)) {
        continue;
      }
      if (!tabList.containsEntry(targetId)) {
        tabList.addEntry(entry.getValue());
      }
      iterator.remove();
    }
    if (removed.isEmpty()) {
      removedEntries.remove(viewerId);
    }
  }

  private static void restoreEntries(TabList tabList, Map<UUID, TabListEntry> removed) {
    for (var iterator = removed.entrySet().iterator(); iterator.hasNext(); ) {
      var entry = iterator.next();
      if (!tabList.containsEntry(entry.getKey())) {
        tabList.addEntry(entry.getValue());
      }
      iterator.remove();
    }
  }

  private boolean isHiddenDestination(String suggestion, Set<UUID> currentVanished) {
    if (suggestion == null) {
      return false;
    }
    for (RegisteredServer server : proxy.getAllServers()) {
      if (server.getServerInfo().getName().equalsIgnoreCase(suggestion)) {
        return ProxyVisibility.isVanishedOnly(connectedIds(server), currentVanished);
      }
    }
    return false;
  }

  private static Set<UUID> connectedIds(RegisteredServer server) {
    Set<UUID> connected = new HashSet<>();
    for (Player player : server.getPlayersConnected()) {
      connected.add(player.getUniqueId());
    }
    return connected;
  }

  private static boolean isServerCompletion(String partialMessage) {
    if (partialMessage == null || partialMessage.isBlank()) {
      return false;
    }
    String trimmed = partialMessage.trim();
    String[] parts = trimmed.split("\\s+");
    if (parts.length > 2 || !parts[0].startsWith("/")) {
      return false;
    }
    String command = parts[0].substring(1).toLowerCase(Locale.ROOT);
    return command.equals("server")
        || command.equals("vservers")
        || command.equals("vanishservers");
  }

  private static Set<UUID> immutableUuids(Set<UUID> uuids, String name) {
    Objects.requireNonNull(uuids, name);
    return Set.copyOf(uuids);
  }
}
