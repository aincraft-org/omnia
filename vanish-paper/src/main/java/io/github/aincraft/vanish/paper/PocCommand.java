package io.github.aincraft.vanish.paper;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Temporary command for the API-only Paper visibility POC. */
public final class PocCommand implements CommandExecutor {
  private final PocListener listener;

  public PocCommand(PocListener listener) {
    this.listener = listener;
  }

  @Override
  public boolean onCommand(
      CommandSender sender, Command command, String label, String[] args) {
    if (args.length != 2) {
      sender.sendMessage("Usage: /" + label + " <on|off> <exact-player-name>");
      return true;
    }

    String action = args[0].toLowerCase(Locale.ROOT);
    if (!action.equals("on") && !action.equals("off")) {
      sender.sendMessage("Usage: /" + label + " <on|off> <exact-player-name>");
      return true;
    }

    Player target = Bukkit.getPlayerExact(args[1]);
    if (target == null) {
      sender.sendMessage("No online player has that exact name.");
      return true;
    }

    boolean vanished = action.equals("on");
    int affected = listener.setVanished(target, vanished);
    String state = vanished ? "hidden" : "shown";
    sender.sendMessage(
        "[vanishpoc] "
            + target.getName()
            + " is now "
            + state
            + " for "
            + affected
            + " viewer(s).");
    return true;
  }
}
