package io.github.aincraft.vanish.paper;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entrypoint for the vanish backend. */
public final class VanishPaperPlugin extends JavaPlugin {
  private VanishManager manager;
  private PlayerListener listener;

  @Override
  public void onEnable() {
    manager = new VanishManager(this);
    listener = new PlayerListener(this, manager);
    getServer().getPluginManager().registerEvents(listener, this);
    listener.start();

    PluginCommand command = getCommand("vanish");
    if (command == null) {
      getLogger().severe("Missing vanish command declaration");
      return;
    }
    command.setExecutor(new VanishCommand(this, this::findTransport));
  }

  @Override
  public void onDisable() {
    if (listener != null) {
      listener.stop();
    }
  }

  /** Exposes the Paper-side state manager to the transport integration. */
  public VanishManager getVanishManager() {
    return manager;
  }

  private VanishTransport findTransport() {
    var registration = getServer().getServicesManager().getRegistration(VanishTransport.class);
    return registration == null ? null : registration.getProvider();
  }
}
