package io.github.aincraft.vanish.paper;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entrypoint for the vanish backend. */
public final class VanishPaperPlugin extends JavaPlugin {
  @Override
  public void onEnable() {
    PocListener listener = new PocListener(this);
    getServer().getPluginManager().registerEvents(listener, this);
    PluginCommand command = getCommand("vanishpoc");
    if (command == null) {
      getLogger().severe("Missing vanishpoc command declaration");
      return;
    }
    command.setExecutor(new PocCommand(listener));
  }
}
