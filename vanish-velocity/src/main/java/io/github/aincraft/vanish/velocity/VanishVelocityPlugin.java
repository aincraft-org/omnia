package io.github.aincraft.vanish.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.vanish.common.VanishState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Velocity entrypoint for the vanish proxy. */
@Plugin(id = "vanish-nopacket")
public final class VanishVelocityPlugin {
  private final Path dataDirectory;
  private final Logger logger;
  private final ProxyServer proxy;
  private final ExecutorService initializationExecutor;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile RedisVelocityService redisService;
  private volatile VanishTabMasker tabMasker;
  private volatile VanishServersCommand serversCommand;
  private volatile ServerConnectionGuard connectionGuard;
  private volatile Consumer<VanishState> stateListener;
  private final Object lifecycleLock = new Object();

  @Inject
  public VanishVelocityPlugin(
      @DataDirectory Path dataDirectory, Logger logger, ProxyServer proxy) {
    this.dataDirectory = dataDirectory;
    this.logger = logger;
    this.proxy = proxy;
    this.initializationExecutor =
        Executors.newSingleThreadExecutor(namedFactory("vanish-velocity-init"));
  }

  VanishVelocityPlugin(Path dataDirectory, Logger logger) {
    this(dataDirectory, logger, null);
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    if (closed.get()) {
      return;
    }
    CompletableFuture.supplyAsync(
            () -> {
              try {
                Files.createDirectories(dataDirectory);
              } catch (IOException failure) {
                throw new IllegalStateException("Unable to create Velocity data directory", failure);
              }
              VelocityConfig config;
              try {
                config = VelocityConfig.from(dataDirectory);
              } catch (IOException failure) {
                throw new IllegalStateException("Unable to read Velocity configuration", failure);
              }
              return new LoadedState(config, VanishStateStore.load(config.stateFile()));
            },
            initializationExecutor)
        .thenAccept(
            loaded -> {
              synchronized (lifecycleLock) {
                if (closed.get()) {
                  return;
                }
                if (!loaded.enabled()) {
                  logger.log(
                      Level.SEVERE,
                      "Vanish state is corrupt; mutations and Redis publication are disabled. Backup: {0}",
                      loaded.backupFile());
                  return;
                }
                RedisVelocityService service =
                    new RedisVelocityService(loaded.store(), loaded.config(), logger);
                if (proxy != null) {
                  VanishState initial = service.snapshot();
                  Set<UUID> initialVanished =
                      initial == null ? Set.of() : initial.vanished();
                  Set<UUID> configuredSeeUuids = loaded.config().configuredSeeUuids();
                  VanishTabMasker masker =
                      new VanishTabMasker(proxy, initialVanished, configuredSeeUuids);
                  VanishServersCommand command =
                      new VanishServersCommand(proxy, initialVanished, configuredSeeUuids);
                  ServerConnectionGuard guard =
                      new ServerConnectionGuard(initialVanished, configuredSeeUuids);
                  Consumer<VanishState> listener =
                      state -> {
                        masker.onStateChanged(state.vanished());
                        command.onStateChanged(state.vanished());
                        guard.onStateChanged(state.vanished());
                      };
                  tabMasker = masker;
                  serversCommand = command;
                  connectionGuard = guard;
                  stateListener = listener;
                  service.addStateListener(listener);
                  proxy.getEventManager().register(this, masker);
                  proxy.getEventManager().register(this, guard);
                  proxy
                      .getCommandManager()
                      .register(
                          proxy
                              .getCommandManager()
                              .metaBuilder("vservers")
                              .aliases("vanishservers")
                              .plugin(this)
                              .build(),
                          command);
                  masker.start(this);
                }
                redisService = service;
                service.start();
              }
            })
        .exceptionally(
            failure -> {
              logger.log(Level.SEVERE, "Unable to initialize authoritative vanish state", failure);
              return null;
            });
  }
  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      RedisVelocityService service = redisService;
      redisService = null;
      Consumer<VanishState> listener = stateListener;
      stateListener = null;
      if (service != null && listener != null) {
        service.removeStateListener(listener);
      }
      VanishTabMasker masker = tabMasker;
      tabMasker = null;
      serversCommand = null;
      connectionGuard = null;
      if (masker != null) {
        masker.close();
      }
      if (service != null) {
        service.close();
      }
    }
    initializationExecutor.shutdownNow();
  }

  public RedisVelocityService redisService() {
    return redisService;
  }

  private record LoadedState(VelocityConfig config, VanishStateStore.LoadResult state) {
    private VanishStateStore store() {
      return state.store();
    }

    private boolean enabled() {
      return state.enabled();
    }

    private Path backupFile() {
      return state.backupFile();
    }
  }


  private static ThreadFactory namedFactory(String prefix) {
    AtomicInteger count = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, prefix + "-" + count.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
