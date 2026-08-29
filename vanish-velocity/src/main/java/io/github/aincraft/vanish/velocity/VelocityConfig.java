package io.github.aincraft.vanish.velocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    long retryMaxMillis,
    Set<UUID> configuredSeeUuids) {
  public static final int DEFAULT_PORT = 6379;
  public static final int DEFAULT_DATABASE = 0;
  public static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_BLOCKING_SOCKET_TIMEOUT_MILLIS = 0;
  public static final long DEFAULT_RETRY_INITIAL_MILLIS = 500L;
  public static final long DEFAULT_RETRY_MAX_MILLIS = 30_000L;

  public VelocityConfig {
    Objects.requireNonNull(configuredSeeUuids, "configuredSeeUuids");
    configuredSeeUuids = Set.copyOf(configuredSeeUuids);
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

  public VelocityConfig(
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
    this(
        stateFile,
        host,
        port,
        username,
        password,
        database,
        connectionTimeoutMillis,
        socketTimeoutMillis,
        blockingSocketTimeoutMillis,
        retryInitialMillis,
        retryMaxMillis,
        Set.of());
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
        DEFAULT_RETRY_MAX_MILLIS,
        Set.of());
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
        DEFAULT_RETRY_MAX_MILLIS,
        Set.of());
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
    List<String> lines = Files.readAllLines(configFile);
    for (int index = 0; index < lines.size(); index++) {
      String trimmed = lines.get(index).trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(":")) {
        continue;
      }
      int separator = trimmed.indexOf(':');
      String key = trimmed.substring(0, separator).trim();
      String value = trimmed.substring(separator + 1).trim();
      if (key.equals("see-uuids") && value.isBlank()) {
        StringBuilder listValue = new StringBuilder();
        while (index + 1 < lines.size()) {
          String item = lines.get(index + 1).trim();
          if (!item.startsWith("-")) {
            break;
          }
          listValue.append(item.substring(1).trim()).append(',');
          index++;
        }
        value = listValue.toString();
      }
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
        longValue(values, "retry-max-millis", defaults.retryMaxMillis()),
        parseSeeUuids(values.get("see-uuids")));
  }

  private static Set<UUID> parseSeeUuids(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    String normalized = value.trim();
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1).trim();
    }
    if (normalized.isBlank()) {
      return Set.of();
    }
    if (normalized.equalsIgnoreCase("false")) {
      return Set.of();
    }
    Set<UUID> result = new HashSet<>();
    for (String token : normalized.split("[,\\s]+")) {
      String candidate = token.trim();
      if ((candidate.startsWith("\"") && candidate.endsWith("\""))
          || (candidate.startsWith("'") && candidate.endsWith("'"))) {
        candidate = candidate.substring(1, candidate.length() - 1);
      }
      try {
        UUID uuid = UUID.fromString(candidate);
        if (!uuid.toString().equals(candidate)) {
          throw new IllegalArgumentException("see-uuids must use canonical UUIDs");
        }
        result.add(uuid);
      } catch (IllegalArgumentException failure) {
        throw new IllegalArgumentException("Invalid see-uuids UUID: " + candidate, failure);
      }
    }
    return Set.copyOf(result);
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
