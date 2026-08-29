package io.github.aincraft.vanish.paper;

import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Redis connection and Paper reconciliation settings. */
public record RedisConfig(
    String host,
    int port,
    String username,
    String password,
    int database,
    String backendId,
    int connectionTimeoutMillis,
    int socketTimeoutMillis,
    int blockingSocketTimeoutMillis,
    long requestTimeoutMillis,
    long retryInitialMillis,
    long retryMaxMillis) {
  public static final int DEFAULT_PORT = 6379;
  public static final int DEFAULT_DATABASE = 0;
  public static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_BLOCKING_SOCKET_TIMEOUT_MILLIS = 0;
  public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 5_000L;
  public static final long DEFAULT_RETRY_INITIAL_MILLIS = 500L;
  public static final long DEFAULT_RETRY_MAX_MILLIS = 30_000L;

  /** Validates Redis settings and retry bounds. */
  public RedisConfig {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(backendId, "backendId");
    if (host.isBlank()) {
      throw new IllegalArgumentException("Redis host must not be blank");
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("Redis port must be between 1 and 65535");
    }
    if (database < 0) {
      throw new IllegalArgumentException("Redis database must not be negative");
    }
    if (backendId.isBlank()) {
      throw new IllegalArgumentException("backend-id must not be blank");
    }
    if (connectionTimeoutMillis < 1 || socketTimeoutMillis < 1) {
      throw new IllegalArgumentException("Redis timeouts must be positive");
    }
    if (blockingSocketTimeoutMillis < 0) {
      throw new IllegalArgumentException("Blocking Redis timeout must not be negative");
    }
    if (requestTimeoutMillis < 1 || retryInitialMillis < 1 || retryMaxMillis < retryInitialMillis) {
      throw new IllegalArgumentException("Invalid request timeout or retry backoff");
    }
  }

  /** Creates settings with default timeout and retry values. */
  public RedisConfig(
      String host, int port, String username, String password, int database, String backendId) {
    this(
        host,
        port,
        username,
        password,
        database,
        backendId,
        DEFAULT_CONNECTION_TIMEOUT_MILLIS,
        DEFAULT_SOCKET_TIMEOUT_MILLIS,
        DEFAULT_BLOCKING_SOCKET_TIMEOUT_MILLIS,
        DEFAULT_REQUEST_TIMEOUT_MILLIS,
        DEFAULT_RETRY_INITIAL_MILLIS,
        DEFAULT_RETRY_MAX_MILLIS);
  }

  /** Creates settings with default database and timeout values. */
  public RedisConfig(String host, int port, String username, String password, String backendId) {
    this(host, port, username, password, DEFAULT_DATABASE, backendId);
  }

  /** Loads settings from a Paper plugin configuration. */
  public static RedisConfig from(JavaPlugin plugin) {
    Objects.requireNonNull(plugin, "plugin");
    return from(plugin.getConfig());
  }

  /** Loads settings from a Bukkit configuration. */
  public static RedisConfig from(FileConfiguration config) {
    Objects.requireNonNull(config, "config");
    return new RedisConfig(
        config.getString("redis.host", "localhost"),
        config.getInt("redis.port", DEFAULT_PORT),
        config.getString("redis.username", ""),
        config.getString("redis.password", ""),
        config.getInt("redis.database", DEFAULT_DATABASE),
        config.getString("backend-id", "paper-local"),
        config.getInt("redis.connection-timeout-ms", DEFAULT_CONNECTION_TIMEOUT_MILLIS),
        config.getInt("redis.socket-timeout-ms", DEFAULT_SOCKET_TIMEOUT_MILLIS),
        config.getInt("redis.blocking-socket-timeout-ms", DEFAULT_BLOCKING_SOCKET_TIMEOUT_MILLIS),
        config.getLong("redis.request-timeout-ms", DEFAULT_REQUEST_TIMEOUT_MILLIS),
        config.getLong("redis.retry-initial-ms", DEFAULT_RETRY_INITIAL_MILLIS),
        config.getLong("redis.retry-max-ms", DEFAULT_RETRY_MAX_MILLIS));
  }
}
