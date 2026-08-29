package io.github.aincraft.vanish.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Public replacement command for server listing and routing without hidden destinations. */
@SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
public final class VanishServersCommand implements SimpleCommand {
  private static final Comparator<RegisteredServer> BY_NAME =
      Comparator.comparing(
              (RegisteredServer server) -> server.getServerInfo().getName(),
              String.CASE_INSENSITIVE_ORDER)
          .thenComparing(server -> server.getServerInfo().getName());

  private final ProxyServer proxy;
  private volatile Set<UUID> configuredSeeUuids;
  private volatile Set<UUID> vanished;

  public VanishServersCommand(ProxyServer proxy, Set<UUID> configuredSeeUuids) {
    this(proxy, Set.of(), configuredSeeUuids);
  }

  public VanishServersCommand(ProxyServer proxy, Set<UUID> vanished, Set<UUID> configuredSeeUuids) {
    this.proxy = Objects.requireNonNull(proxy, "proxy");
    this.vanished = immutableUuids(vanished, "vanished");
    this.configuredSeeUuids = immutableUuids(configuredSeeUuids, "configuredSeeUuids");
  }

  /** Updates the read-only authority view used by listing, routing, and suggestions. */
  public void onStateChanged(Set<UUID> vanished) {
    this.vanished = immutableUuids(vanished, "vanished");
  }

  /** Replaces configured viewers used by future list, route, and suggestion operations. */
  public void onConfiguredSeeUuidsChanged(Set<UUID> configuredSeeUuids) {
    this.configuredSeeUuids = immutableUuids(configuredSeeUuids, "configuredSeeUuids");
  }

  @Override
  public void execute(Invocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    CommandSource source = invocation.source();
    boolean canSee = canSee(source);
    List<RegisteredServer> visible = visibleServers(canSee);
    String[] arguments = invocation.arguments();
    if (arguments.length == 0) {
      source.sendPlainMessage(formatServerList(visible));
      return;
    }
    if (arguments.length != 1 || arguments[0].isBlank()) {
      source.sendPlainMessage("Usage: /vservers [server]");
      return;
    }
    if (!(source instanceof Player player)) {
      source.sendPlainMessage("Only players can connect to a server.");
      return;
    }
    RegisteredServer destination = findServer(arguments[0]).orElse(null);
    if (destination == null || !isVisible(destination, canSee)) {
      source.sendPlainMessage("That server is unavailable.");
      return;
    }
    player.createConnectionRequest(destination).fireAndForget();
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    String[] arguments = invocation.arguments();
    boolean canSee = canSee(invocation.source());
    if (arguments.length > 1) {
      return List.of();
    }
    String prefix = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (RegisteredServer server : visibleServers(canSee)) {
      String name = server.getServerInfo().getName();
      if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        suggestions.add(name);
      }
    }
    return List.copyOf(suggestions);
  }

  private List<RegisteredServer> visibleServers(boolean canSee) {
    List<RegisteredServer> servers = new ArrayList<>(proxy.getAllServers());
    servers.sort(BY_NAME);
    if (canSee) {
      return servers;
    }
    servers.removeIf(server -> !isVisible(server, false));
    return servers;
  }

  private boolean isVisible(RegisteredServer server, boolean canSee) {
    return canSee || !ProxyVisibility.isVanishedOnly(connectedIds(server), vanished);
  }

  private Optional<RegisteredServer> findServer(String name) {
    return proxy.getAllServers().stream()
        .filter(server -> server.getServerInfo().getName().equalsIgnoreCase(name))
        .findFirst();
  }

  private boolean canSee(Player player) {
    return ProxyVisibility.canSeeVanished(player, configuredSeeUuids);
  }

  private boolean canSee(CommandSource source) {
    return !(source instanceof Player player) || canSee(player);
  }

  private static String formatServerList(Collection<RegisteredServer> servers) {
    if (servers.isEmpty()) {
      return "Servers: none";
    }
    return "Servers: "
        + servers.stream()
            .map(server -> server.getServerInfo().getName())
            .collect(Collectors.joining(", "));
  }

  private static Set<UUID> connectedIds(RegisteredServer server) {
    Set<UUID> connected = new HashSet<>();
    for (Player player : server.getPlayersConnected()) {
      connected.add(player.getUniqueId());
    }
    return connected;
  }

  private static Set<UUID> immutableUuids(Set<UUID> uuids, String name) {
    Objects.requireNonNull(uuids, name);
    return Set.copyOf(uuids);
  }
}
