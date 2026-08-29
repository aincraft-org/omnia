package io.github.aincraft.vanish.paper;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entrypoint for the vanish backend. */
@SuppressWarnings("PMD.NullAssignment")
public final class VanishPaperPlugin extends JavaPlugin {
  private VanishManager manager;
  private PlayerListener listener;
  private RedisPaperService redis;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    manager = new VanishManager(this);
    redis = new RedisPaperService(this, manager, RedisConfig.from(this));
    getServer()
        .getServicesManager()
        .register(VanishTransport.class, redis, this, ServicePriority.Normal);
    listener = new PlayerListener(this, manager, redis);
    getServer().getPluginManager().registerEvents(listener, this);
    redis.start();
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
    if (redis != null) {
      getServer().getServicesManager().unregister(VanishTransport.class, redis);
      redis.close();
      redis = null;
    }
  }

  /** Exposes the Paper-side state manager to the transport integration. */
  public VanishManager getVanishManager() {
    return manager;
  }

  /** Exposes the Redis transport for integrations that need an asynchronous authority boundary. */
  public RedisPaperService getRedisPaperService() {
    return redis;
  }

  private VanishTransport findTransport() {
    var registration = getServer().getServicesManager().getRegistration(VanishTransport.class);
    return registration == null ? null : registration.getProvider();
  }
}
