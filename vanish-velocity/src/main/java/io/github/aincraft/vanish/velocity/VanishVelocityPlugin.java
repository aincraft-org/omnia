package io.github.aincraft.vanish.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Velocity entrypoint for the vanish proxy. */
@Plugin(id = "vanish-nopacket")
public final class VanishVelocityPlugin {
  private final Path dataDirectory;
  private final Logger logger;
  private final ExecutorService initializationExecutor;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile RedisVelocityService redisService;
  private final Object lifecycleLock = new Object();

  @Inject
  public VanishVelocityPlugin(@DataDirectory Path dataDirectory, Logger logger) {
    this.dataDirectory = dataDirectory;
    this.logger = logger;
    this.initializationExecutor =
        Executors.newSingleThreadExecutor(namedFactory("vanish-velocity-init"));
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
