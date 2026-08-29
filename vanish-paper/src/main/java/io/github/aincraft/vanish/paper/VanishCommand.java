package io.github.aincraft.vanish.paper;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.VanishState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Handles vanish requests without changing local state before authority confirmation. */
public final class VanishCommand implements TabExecutor {
  private final JavaPlugin plugin;
  private final Supplier<? extends VanishTransport> transport;

  public VanishCommand(JavaPlugin plugin, VanishTransport transport) {
    this(plugin, () -> transport);
  }

  public VanishCommand(JavaPlugin plugin, Supplier<? extends VanishTransport> transport) {
    this.plugin = plugin;
    this.transport = transport;
  }

  @Override
  public boolean onCommand(
      CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
      if (!sender.hasPermission(Permissions.ADMIN)) {
        send(sender, "You do not have permission to view vanish status.");
        return true;
      }
      requestStatus(sender);
      return true;
    }

    if (args.length > 1) {
      send(sender, "Usage: /" + label + " [player|status]");
      return true;
    }

    Player target;
    if (args.length == 0) {
      if (!(sender instanceof Player player)) {
        send(sender, "Only a player can toggle their own vanish state.");
        return true;
      }
      target = player;
      if (!sender.hasPermission(Permissions.USE)) {
        send(sender, "You do not have permission to vanish yourself.");
        return true;
      }
    } else {
      target = plugin.getServer().getPlayerExact(args[0]);
      if (target == null) {
        send(sender, "No online player has that exact name.");
        return true;
      }
      boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
      String permission = self ? Permissions.USE : Permissions.OTHERS;
      if (!sender.hasPermission(permission)) {
        send(sender, "You do not have permission to vanish that player.");
        return true;
      }
    }

    requestToggle(sender, target);
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (args.length != 1) {
      return List.of();
    }
    String prefix = args[0].toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    if (sender.hasPermission(Permissions.ADMIN) && "status".startsWith(prefix)) {
      suggestions.add("status");
    }
    if (sender.hasPermission(Permissions.OTHERS)) {
      for (Player player : plugin.getServer().getOnlinePlayers()) {
        if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
          suggestions.add(player.getName());
        }
      }
    }
    return suggestions;
  }

  private void requestToggle(CommandSender sender, Player target) {
    VanishTransport authority = authority(sender);
    if (authority == null) {
      return;
    }
    UUID targetId = target.getUniqueId();
    String targetName = target.getName();
    CompletionStage<VanishState> snapshot;
    try {
      snapshot = authority.readSnapshot();
    } catch (RuntimeException exception) {
      sendFailure(sender, exception);
      return;
    }
    if (snapshot == null) {
      send(sender, "Vanish authority is unavailable.");
      return;
    }

    snapshot.whenComplete(
        (state, error) -> {
          if (error != null) {
            sendFailure(sender, error);
            return;
          }
          if (state == null) {
            send(sender, "Vanish authority returned no state.");
            return;
          }
          ChangeRequest request =
              new ChangeRequest(
                  UUID.randomUUID(), targetId, !state.vanished().contains(targetId));
          CompletionStage<ChangeAck> response;
          try {
            response = authority.requestChange(request);
          } catch (RuntimeException exception) {
            sendFailure(sender, exception);
            return;
          }
          if (response == null) {
            send(sender, "Vanish authority is unavailable.");
            return;
          }
          response.whenComplete(
              (ack, changeError) -> {
                if (changeError != null) {
                  sendFailure(sender, changeError);
                } else if (ack == null) {
                  send(sender, "Vanish authority returned no acknowledgement.");
                } else if (!ack.accepted()) {
                  String reason = ack.error().isBlank() ? "request rejected" : ack.error();
                  send(sender, "Vanish request rejected: " + reason);
                } else {
                  String stateLabel = request.vanished() ? "vanished" : "visible";
                  send(sender, targetName + " is now " + stateLabel + ".");
                }
              });
        });
  }

  private void requestStatus(CommandSender sender) {
    VanishTransport authority = authority(sender);
    if (authority == null) {
      return;
    }
    CompletionStage<VanishState> snapshot;
    try {
      snapshot = authority.readSnapshot();
    } catch (RuntimeException exception) {
      sendFailure(sender, exception);
      return;
    }
    if (snapshot == null) {
      send(sender, "Vanish authority is unavailable.");
      return;
    }
    snapshot.whenComplete(
        (state, error) -> {
          if (error != null) {
            sendFailure(sender, error);
          } else if (state == null) {
            send(sender, "Vanish authority returned no state.");
          } else {
            String players =
                state.vanished().stream()
                    .sorted(Comparator.comparing(UUID::toString))
                    .map(UUID::toString)
                    .collect(Collectors.joining(", "));
            if (players.isEmpty()) {
              players = "none";
            }
            send(sender, "Vanish status (version " + state.version() + "): " + players);
          }
        });
  }

  private VanishTransport authority(CommandSender sender) {
    VanishTransport authority;
    try {
      authority = transport.get();
    } catch (RuntimeException exception) {
      sendFailure(sender, exception);
      return null;
    }
    if (authority == null) {
      send(sender, "Vanish authority is unavailable.");
    }
    return authority;
  }

  private void sendFailure(CommandSender sender, Throwable error) {
    Throwable cause = error;
    while (cause.getCause() != null
        && (cause instanceof java.util.concurrent.CompletionException
            || cause instanceof java.util.concurrent.ExecutionException)) {
      cause = cause.getCause();
    }
    String detail = cause.getMessage();
    send(sender, "Vanish authority request failed" + (detail == null ? "." : ": " + detail));
  }

  private void send(CommandSender sender, String message) {
    Runnable send = () -> sender.sendMessage(message);
    if (org.bukkit.Bukkit.isPrimaryThread()) {
      send.run();
    } else {
      plugin.getServer().getScheduler().runTask(plugin, send);
    }
  }
}
