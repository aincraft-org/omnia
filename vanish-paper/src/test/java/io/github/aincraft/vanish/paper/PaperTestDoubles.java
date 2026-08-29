package io.github.aincraft.vanish.paper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

final class PaperTestDoubles {
  private static FakeServerState activeServer;

  private PaperTestDoubles() {}

  static Server server(Collection<FakePlayer> players) {
    if (activeServer == null) {
      activeServer = new FakeServerState();
      InvocationHandler handler =
          (proxy, method, args) -> {
            return switch (method.getName()) {
              case "getOnlinePlayers" ->
                  activeServer.online.stream().map(FakePlayer::player).toList();
              case "getPlayerExact" ->
                  activeServer.online.stream()
                      .filter(player -> player.name().equals(args[0]))
                      .map(FakePlayer::player)
                      .findFirst()
                      .orElse(null);
              case "getPlayer" -> {
                if (args[0] instanceof UUID uuid) {
                  yield activeServer.online.stream()
                      .filter(player -> player.id().equals(uuid))
                      .map(FakePlayer::player)
                      .findFirst()
                      .orElse(null);
                }
                yield null;
              }
              case "getScheduler" -> activeServer.scheduler;
              case "isPrimaryThread" -> true;
              case "getLogger" -> Logger.getLogger("test-server");
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              case "toString" -> "test-server";
              default -> defaultValue(method.getReturnType());
            };
          };
      activeServer.server =
          (Server)
              Proxy.newProxyInstance(
                  Server.class.getClassLoader(), new Class<?>[] {Server.class}, handler);
      try {
        Bukkit.setServer(activeServer.server);
      } catch (NoSuchElementException | UnsupportedOperationException ignored) {
        // Paper's API-only ServerBuildInfo is absent in unit tests; the singleton is still set.
      }
    }
    activeServer.online.clear();
    activeServer.online.addAll(players);
    return activeServer.server;
  }

  static Plugin plugin(Server server) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          return switch (method.getName()) {
            case "getServer" -> server;
            case "getName" -> "test-plugin";
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "test-plugin";
            default -> defaultValue(method.getReturnType());
          };
        };
    return (Plugin)
        Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
  }

  static CommandSender sender(String name, String... permissions) {
    Map<String, Boolean> granted = new HashMap<>();
    for (String permission : permissions) {
      granted.put(permission, true);
    }
    List<String> messages = new ArrayList<>();
    InvocationHandler handler =
        (proxy, method, args) -> {
          return switch (method.getName()) {
            case "hasPermission" -> granted.getOrDefault(args[0], false);
            case "sendMessage" -> {
              if (args.length > 0 && args[0] instanceof String message) {
                messages.add(message);
              }
              yield null;
            }
            case "getName" -> name;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> name;
            default -> defaultValue(method.getReturnType());
          };
        };
    return (CommandSender)
        Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class}, handler);
  }

  static BukkitScheduler scheduler() {
    InvocationHandler handler =
        (proxy, method, args) -> {
          return switch (method.getName()) {
            case "runTask", "runTaskLater" -> {
              if (args.length > 1 && args[1] instanceof Runnable task) {
                task.run();
              }
              yield task();
            }
            case "runTaskTimer" -> task();
            default -> defaultValue(method.getReturnType());
          };
        };
    return (BukkitScheduler)
        Proxy.newProxyInstance(
            BukkitScheduler.class.getClassLoader(), new Class<?>[] {BukkitScheduler.class}, handler);
  }

  static BukkitTask task() {
    InvocationHandler handler =
        (proxy, method, args) -> {
          return switch (method.getName()) {
            case "isCancelled" -> false;
            case "getTaskId" -> 1;
            case "getOwner" -> null;
            case "cancel" -> null;
            default -> defaultValue(method.getReturnType());
          };
        };
    return (BukkitTask)
        Proxy.newProxyInstance(
            BukkitTask.class.getClassLoader(), new Class<?>[] {BukkitTask.class}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    return null;
  }

  private static final class FakeServerState {
    private final List<FakePlayer> online = new ArrayList<>();
    private final BukkitScheduler scheduler = scheduler();
    private Server server;
  }

  static final class FakePlayer {
    private final UUID id;
    private final String name;
    private final Map<String, Boolean> permissions = new HashMap<>();
    private final Map<UUID, Boolean> visible = new HashMap<>();
    private final List<String> messages = new ArrayList<>();
    private int hideCalls;
    private int showCalls;
    private final Player player;

    FakePlayer(UUID id, String name, String... grantedPermissions) {
      this.id = id;
      this.name = name;
      for (String permission : grantedPermissions) {
        permissions.put(permission, true);
      }
      InvocationHandler handler = this::invoke;
      player =
          (Player)
              Proxy.newProxyInstance(
                  Player.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    UUID id() {
      return id;
    }

    String name() {
      return name;
    }

    Player player() {
      return player;
    }

    int hideCalls() {
      return hideCalls;
    }

    int showCalls() {
      return showCalls;
    }

    List<String> messages() {
      return messages;
    }

    private Object invoke(Object proxy, Method method, Object[] args) {
      return switch (method.getName()) {
        case "getUniqueId" -> id;
        case "getName", "getDisplayName" -> name;
        case "hasPermission" -> permissions.getOrDefault(args[0], false);
        case "canSee" -> visible.getOrDefault(((Player) args[0]).getUniqueId(), true);
        case "hidePlayer" -> {
          Player target = (Player) args[args.length - 1];
          visible.put(target.getUniqueId(), false);
          hideCalls++;
          yield null;
        }
        case "showPlayer" -> {
          Player target = (Player) args[args.length - 1];
          visible.put(target.getUniqueId(), true);
          showCalls++;
          yield null;
        }
        case "sendMessage" -> {
          if (args.length > 0 && args[0] instanceof String message) {
            messages.add(message);
          }
          yield null;
        }
        case "equals" -> proxy == args[0];
        case "hashCode" -> System.identityHashCode(proxy);
        case "toString" -> name;
        default -> defaultValue(method.getReturnType());
      };
    }
  }
}
