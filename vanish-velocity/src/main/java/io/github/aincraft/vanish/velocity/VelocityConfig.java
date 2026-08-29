package io.github.aincraft.vanish.velocity;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;

/** Redis and durable-state settings used by the Velocity authority. */
public record VelocityConfig(
    Path stateFile,
    String host,
    int port,
    String username,
    String password,
    int database,
    int connectionTimeoutMillis,
    int socketTimeoutMillis,
    int blockingSocketTimeoutMillis,
    long retryInitialMillis,
    long retryMaxMillis) {
  public static final int DEFAULT_PORT = 6379;
  public static final int DEFAULT_DATABASE = 0;
  public static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_BLOCKING_SOCKET_TIMEOUT_MILLIS = 0;
  public static final long DEFAULT_RETRY_INITIAL_MILLIS = 500L;
  public static final long DEFAULT_RETRY_MAX_MILLIS = 30_000L;

  public VelocityConfig {
    Objects.requireNonNull(stateFile, "stateFile");
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(password, "password");
    if (host.isBlank()) {
      throw new IllegalArgumentException("Redis host must not be blank");
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("Redis port must be between 1 and 65535");
    }
    if (database < 0) {
      throw new IllegalArgumentException("Redis database must not be negative");
    }
    if (connectionTimeoutMillis < 1 || socketTimeoutMillis < 1) {
      throw new IllegalArgumentException("Redis timeouts must be positive");
    }
    if (blockingSocketTimeoutMillis < 0) {
      throw new IllegalArgumentException("Blocking Redis timeout must not be negative");
    }
    if (retryInitialMillis < 1 || retryMaxMillis < retryInitialMillis) {
      throw new IllegalArgumentException("Invalid Redis retry backoff");
    }
  }

  public VelocityConfig(Path stateFile) {
    this(
        stateFile,
        "localhost",
        DEFAULT_PORT,
        "",
        "",
        DEFAULT_DATABASE,
        DEFAULT_CONNECTION_TIMEOUT_MILLIS,
        DEFAULT_SOCKET_TIMEOUT_MILLIS,
        DEFAULT_BLOCKING_SOCKET_TIMEOUT_MILLIS,
        DEFAULT_RETRY_INITIAL_MILLIS,
        DEFAULT_RETRY_MAX_MILLIS);
  }

  public VelocityConfig(
      Path stateFile, String host, int port, String username, String password, int database) {
    this(
        stateFile,
        host,
        port,
        username,
        password,
        database,
        DEFAULT_CONNECTION_TIMEOUT_MILLIS,
        DEFAULT_SOCKET_TIMEOUT_MILLIS,
        DEFAULT_BLOCKING_SOCKET_TIMEOUT_MILLIS,
        DEFAULT_RETRY_INITIAL_MILLIS,
        DEFAULT_RETRY_MAX_MILLIS);
  }

  /** Loads the optional flat YAML settings file in the proxy data directory. */
  public static VelocityConfig from(Path dataDirectory) throws IOException {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    VelocityConfig defaults = defaults(dataDirectory);
    Path configFile = dataDirectory.resolve("config.yml");
    if (Files.notExists(configFile)) {
      return defaults;
    }
    Map<String, String> values = new HashMap<>();
    for (String line : Files.readAllLines(configFile)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(":")) {
        continue;
      }
      int separator = trimmed.indexOf(':');
      String key = trimmed.substring(0, separator).trim();
      String value = trimmed.substring(separator + 1).trim();
      if ((value.startsWith("\"") && value.endsWith("\""))
          || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.substring(1, value.length() - 1);
      }
      values.put(key, value);
    }
    String configuredStateFile = values.get("state-file");
    if (configuredStateFile != null && configuredStateFile.isBlank()) {
      throw new IllegalArgumentException("state-file must not be blank");
    }
    if (configuredStateFile == null) {
      configuredStateFile = "vanish-state.json";
    }
    Path stateFile = Path.of(configuredStateFile);
    if (!stateFile.isAbsolute()) {
      stateFile = dataDirectory.resolve(stateFile);
    }
    return new VelocityConfig(
        stateFile,
        values.getOrDefault("host", defaults.host()),
        integer(values, "port", defaults.port()),
        values.getOrDefault("username", defaults.username()),
        values.getOrDefault("password", defaults.password()),
        integer(values, "database", defaults.database()),
        integer(values, "connection-timeout-millis", defaults.connectionTimeoutMillis()),
        integer(values, "socket-timeout-millis", defaults.socketTimeoutMillis()),
        integer(values, "blocking-socket-timeout-millis", defaults.blockingSocketTimeoutMillis()),
        longValue(values, "retry-initial-millis", defaults.retryInitialMillis()),
        longValue(values, "retry-max-millis", defaults.retryMaxMillis()));
  }

  private static int integer(Map<String, String> values, String key, int fallback) {
    return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
  }

  private static long longValue(Map<String, String> values, String key, long fallback) {
    return values.containsKey(key) ? Long.parseLong(values.get(key)) : fallback;
  }

  public VelocityConfig(
      String host, int port, String username, String password, int database, Path stateFile) {
    this(stateFile, host, port, username, password, database);
  }

  public VelocityConfig(String host, int port, String username, String password, Path stateFile) {
    this(stateFile, host, port, username, password, DEFAULT_DATABASE);
  }

  public static VelocityConfig defaults(Path dataDirectory) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    return new VelocityConfig(dataDirectory.resolve("vanish-state.json"));
  }
}
