package io.github.aincraft.vanish.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/** Denies direct connections to destinations occupied only by vanished players. */
public final class ServerConnectionGuard {
  private static final Component UNAVAILABLE = Component.text("That server is unavailable.");

  private volatile Set<UUID> configuredSeeUuids;
  private volatile Set<UUID> vanished;

  public ServerConnectionGuard(Set<UUID> configuredSeeUuids) {
    this(Set.of(), configuredSeeUuids);
  }

  public ServerConnectionGuard(Set<UUID> vanished, Set<UUID> configuredSeeUuids) {
    this.vanished = immutableUuids(vanished, "vanished");
    this.configuredSeeUuids = immutableUuids(configuredSeeUuids, "configuredSeeUuids");
  }

  /** Updates the read-only authority view used by the pre-connect guard. */
  public void onStateChanged(Set<UUID> vanished) {
    this.vanished = immutableUuids(vanished, "vanished");
  }
  /** Replaces configured viewers used by future connection checks. */
  public void onConfiguredSeeUuidsChanged(Set<UUID> configuredSeeUuids) {
    this.configuredSeeUuids = immutableUuids(configuredSeeUuids, "configuredSeeUuids");
  }

  @Subscribe
  public void onServerPreConnect(ServerPreConnectEvent event) {
    Player viewer = event.getPlayer();
    if (ProxyVisibility.canSeeVanished(viewer, configuredSeeUuids)) {
      return;
    }
    RegisteredServer destination = event.getOriginalServer();
    if (destination == null || !isVanishedOnly(destination)) {
      return;
    }
    event.setResult(ServerPreConnectEvent.ServerResult.denied());
    viewer.sendMessage(UNAVAILABLE);
  }

  private boolean isVanishedOnly(RegisteredServer destination) {
    Set<UUID> connected = new HashSet<>();
    for (Player player : destination.getPlayersConnected()) {
      connected.add(player.getUniqueId());
    }
    return ProxyVisibility.isVanishedOnly(connected, vanished);
  }

  private static Set<UUID> immutableUuids(Set<UUID> uuids, String name) {
    Objects.requireNonNull(uuids, name);
    return Set.copyOf(uuids);
  }
}
