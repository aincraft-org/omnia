package io.github.aincraft.vanish.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VelocitySurfaceTest {
  private static final UUID VIEWER =
      UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID VANISHED =
      UUID.fromString("00000000-0000-0000-0000-000000000011");

  @Test
  void tabMaskerRestoresTheSameEntryAfterUnvanish() {
    Map<UUID, TabListEntry> entries = new HashMap<>();
    TabListEntry vanishedEntry = entry();
    entries.put(VANISHED, vanishedEntry);
    TabList tabList = tabList(entries);
    Player viewer = player(VIEWER, tabList, null);
    ProxyServer proxy = proxyServer(List.of(viewer), List.of());
    VanishTabMasker masker = new VanishTabMasker(proxy, Set.of(VANISHED), Set.of());

    masker.reconcile(viewer);
    assertFalse(entries.containsKey(VANISHED));

    masker.onStateChanged(Set.of());
    assertSame(vanishedEntry, entries.get(VANISHED));
  }

  @Test
  void tabMaskerDoesNotTouchEntriesOwnedByAnotherPlugin() {
    Map<UUID, TabListEntry> entries = new HashMap<>();
    TabListEntry visibleEntry = entry();
    entries.put(VIEWER, visibleEntry);
    TabList tabList = tabList(entries);
    Player viewer = player(VIEWER, tabList, null);
    ProxyServer proxy = proxyServer(List.of(viewer), List.of());
    VanishTabMasker masker = new VanishTabMasker(proxy, Set.of(VANISHED), Set.of());

    masker.reconcile(viewer);

    assertSame(visibleEntry, entries.get(VIEWER));
  }

  @Test
  void directConnectionToVanishedOnlyServerIsDeniedButSeeViewerIsAllowed() {
    Player hidden = player(VANISHED, tabList(new HashMap<>()), null);
    RegisteredServer destination = registeredServer("hidden", List.of(hidden));
    AtomicReference<String> message = new AtomicReference<>();
    Player viewer = player(VIEWER, tabList(new HashMap<>()), message);
    ServerConnectionGuard guard =
        new ServerConnectionGuard(Set.of(VANISHED), Set.of());

    ServerPreConnectEvent deniedEvent = new ServerPreConnectEvent(viewer, destination);
    guard.onServerPreConnect(deniedEvent);

    assertFalse(deniedEvent.getResult().isAllowed());
    assertTrue(message.get().contains("unavailable"));

    ServerConnectionGuard seeGuard =
        new ServerConnectionGuard(Set.of(VANISHED), Set.of(VIEWER));
    ServerPreConnectEvent allowedEvent = new ServerPreConnectEvent(viewer, destination);
    seeGuard.onServerPreConnect(allowedEvent);

    assertTrue(allowedEvent.getResult().isAllowed());
  }

  @Test
  void tabCompletionFiltersHiddenDestinationNames() {
    Player hidden = player(VANISHED, tabList(new HashMap<>()), null);
    RegisteredServer hiddenServer = registeredServer("hidden", List.of(hidden));
    RegisteredServer visibleServer = registeredServer("visible", List.of());
    Player viewer = player(VIEWER, tabList(new HashMap<>()), null);
    ProxyServer proxy = proxyServer(List.of(viewer), List.of(hiddenServer, visibleServer));
    VanishTabMasker masker = new VanishTabMasker(proxy, Set.of(VANISHED), Set.of());
    TabCompleteEvent event =
        new TabCompleteEvent(viewer, "/server ", List.of("hidden", "visible"));

    masker.onTabComplete(event);

    assertEquals(List.of("visible"), event.getSuggestions());
  }

  @Test
  void vserversDoesNotRouteHiddenDestination() {
    Player hidden = player(VANISHED, tabList(new HashMap<>()), null);
    RegisteredServer destination = registeredServer("hidden", List.of(hidden));
    AtomicBoolean connected = new AtomicBoolean();
    ConnectionRequestBuilder request =
        proxy(
            ConnectionRequestBuilder.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("fireAndForget")) {
                connected.set(true);
              }
              return null;
            });
    Player viewer = player(VIEWER, tabList(new HashMap<>()), null, request);
    ProxyServer proxy = proxyServer(List.of(viewer), List.of(destination));
    VanishServersCommand command = new VanishServersCommand(proxy, Set.of(VANISHED), Set.of());

    command.execute(new SimpleInvocation(viewer, new String[] {"hidden"}));

    assertFalse(connected.get());
  }

  private static TabList tabList(Map<UUID, TabListEntry> entries) {
    return proxy(
        TabList.class,
        (ignored, method, arguments) -> {
          return switch (method.getName()) {
            case "containsEntry" -> entries.containsKey(arguments[0]);
            case "removeEntry" -> Optional.ofNullable(entries.remove(arguments[0]));
            case "addEntry" -> {
              TabListEntry entry = (TabListEntry) arguments[0];
              entries.put(VANISHED, entry);
              yield null;
            }
            default -> defaultValue(method.getReturnType());
          };
        });
  }

  private static TabListEntry entry() {
    return proxy(
        TabListEntry.class,
        (ignored, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static Player player(UUID id, TabList tabList, AtomicReference<String> message) {
    return player(id, tabList, message, null);
  }

  private static Player player(
      UUID id, TabList tabList, AtomicReference<String> message, ConnectionRequestBuilder request) {
    return proxy(
        Player.class,
        (ignored, method, arguments) -> {
          return switch (method.getName()) {
            case "getCurrentServer" -> Optional.empty();
            case "getUniqueId" -> id;
            case "getTabList" -> tabList;
            case "sendMessage" -> {
              if (message != null) {
                message.set(String.valueOf(arguments[0]));
              }
              yield null;
            }
            case "createConnectionRequest" -> request;
            default -> defaultValue(method.getReturnType());
          };
        });
  }

  private static RegisteredServer registeredServer(String name, List<Player> players) {
    ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    return proxy(
        RegisteredServer.class,
        (ignored, method, arguments) -> {
          return switch (method.getName()) {
            case "getPlayersConnected" -> players;
            case "getServerInfo" -> info;
            default -> defaultValue(method.getReturnType());
          };
        });
  }

  private static ProxyServer proxyServer(List<Player> players, List<RegisteredServer> servers) {
    return proxy(
        ProxyServer.class,
        (ignored, method, arguments) -> {
          return switch (method.getName()) {
            case "getAllPlayers" -> players;
            case "getAllServers" -> servers;
            default -> defaultValue(method.getReturnType());
          };
        });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T)
        Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == byte.class || type == short.class || type == int.class || type == long.class) {
      return 0;
    }
    if (type == float.class || type == double.class) {
      return 0.0;
    }
    if (type == char.class) {
      return '\0';
    }
    return null;
  }

  private static final class SimpleInvocation implements SimpleCommand.Invocation {
    private final Player source;
    private final String[] arguments;

    private SimpleInvocation(Player source, String[] arguments) {
      this.source = source;
      this.arguments = arguments;
    }

    @Override
    public String alias() {
      return "vservers";
    }

    @Override
    public Player source() {
      return source;
    }

    @Override
    public String[] arguments() {
      return arguments;
    }
  }
}
