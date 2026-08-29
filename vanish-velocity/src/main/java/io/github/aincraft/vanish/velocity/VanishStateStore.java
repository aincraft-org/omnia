package io.github.aincraft.vanish.velocity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.StateDelta;
import io.github.aincraft.vanish.common.VanishState;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable, proxy-owned versioned vanish state. */
public final class VanishStateStore {
  private static final VanishState EMPTY_STATE = new VanishState(0, Set.of());

  private final Path file;
  private final FileWriter fileWriter;
  private VanishState state;
  private boolean enabled;
  private boolean validSnapshot;

  private VanishStateStore(
      Path file, VanishState state, boolean enabled, boolean validSnapshot, FileWriter fileWriter) {
    this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    this.state = Objects.requireNonNull(state, "state");
    this.enabled = enabled;
    this.validSnapshot = validSnapshot;
    this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter");
  }

  /** Loads a state file, preserving a malformed file before disabling mutations. */
  public static LoadResult load(Path file) {
    Objects.requireNonNull(file, "file");
    Path normalized = file.toAbsolutePath().normalize();
    if (Files.notExists(normalized)) {
      VanishStateStore store =
          new VanishStateStore(normalized, EMPTY_STATE, true, true, VanishStateStore::writeAtomically);
      return new LoadResult(store, null, null);
    }

    try {
      String json = Files.readString(normalized, StandardCharsets.UTF_8);
      VanishState state = decode(json);
      VanishStateStore store =
          new VanishStateStore(normalized, state, true, true, VanishStateStore::writeAtomically);
      return new LoadResult(store, null, null);
    } catch (IOException | RuntimeException failure) {
      Path backup = preserveCorruptFile(normalized);
      VanishStateStore store =
          new VanishStateStore(normalized, EMPTY_STATE, false, false, VanishStateStore::writeAtomically);
      return new LoadResult(store, backup, failure);
    }
  }

  /** Returns the latest valid in-memory state, without performing file or Redis I/O. */
  public synchronized VanishState snapshot() {
    return state;
  }

  /** Applies one desired-state request in the caller's serialization order. */
  public synchronized ChangeResult apply(ChangeRequest request) {
    Objects.requireNonNull(request, "request");
    if (!enabled) {
      return rejected(request, "State store is disabled");
    }

    boolean currentlyVanished = state.vanished().contains(request.playerId());
    if (currentlyVanished == request.vanished()) {
      ChangeAck ack = new ChangeAck(request.requestId(), true, state.version(), "");
      return new ChangeResult(ack, null, state);
    }

    if (state.version() == Long.MAX_VALUE) {
      return rejected(request, "State version exhausted");
    }

    Set<UUID> vanished = new LinkedHashSet<>(state.vanished());
    if (request.vanished()) {
      vanished.add(request.playerId());
    } else {
      vanished.remove(request.playerId());
    }
    VanishState next = new VanishState(state.version() + 1, vanished);
    try {
      fileWriter.write(file, next);
    } catch (IOException | RuntimeException failure) {
      ChangeAck ack =
          new ChangeAck(
              request.requestId(), false, state.version(), "Unable to persist vanish state: " + failure.getMessage());
      return new ChangeResult(ack, null, state);
    }

    state = next;
    validSnapshot = true;
    StateDelta delta = new StateDelta(next.version(), request.playerId(), request.vanished());
    ChangeAck ack = new ChangeAck(request.requestId(), true, next.version(), "");
    return new ChangeResult(ack, delta, next);
  }

  /** True when loading succeeded and new mutations are permitted. */
  public synchronized boolean enabled() {
    return enabled;
  }

  /** True when this store has a valid state that may be exposed to consumers. */
  public synchronized boolean hasValidSnapshot() {
    return validSnapshot;
  }

  private ChangeResult rejected(ChangeRequest request, String error) {
    ChangeAck ack = new ChangeAck(request.requestId(), false, state.version(), error);
    return new ChangeResult(ack, null, state);
  }

  private static VanishState decode(String json) {
    JsonElement element;
    try {
      JsonReader reader = new JsonReader(new StringReader(json));
      reader.setStrictness(Strictness.STRICT);
      element = JsonParser.parseReader(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new IllegalArgumentException("Trailing JSON content");
      }
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Malformed state JSON", failure);
    }
    if (!element.isJsonObject()) {
      throw new IllegalArgumentException("State must be a JSON object");
    }
    JsonObject object = element.getAsJsonObject();
    requireExactFields(object, Set.of("version", "vanished"), "state");

    JsonElement versionElement = required(object, "version");
    if (!versionElement.isJsonPrimitive() || !versionElement.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("Field version must be an integer");
    }
    long version;
    try {
      version = versionElement.getAsBigDecimal().toBigIntegerExact().longValueExact();
    } catch (ArithmeticException | NumberFormatException failure) {
      throw new IllegalArgumentException("Field version must be an integer", failure);
    }
    if (version < 0) {
      throw new IllegalArgumentException("Field version must not be negative");
    }

    JsonElement vanishedElement = required(object, "vanished");
    if (!vanishedElement.isJsonObject()) {
      throw new IllegalArgumentException("Field vanished must be an object");
    }
    Set<UUID> vanished = new LinkedHashSet<>();
    for (var entry : vanishedElement.getAsJsonObject().entrySet()) {
      UUID playerId = parseUuid(entry.getKey());
      JsonElement value = entry.getValue();
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
        throw new IllegalArgumentException("Vanish value for " + entry.getKey() + " must be boolean");
      }
      if (value.getAsBoolean()) {
        vanished.add(playerId);
      }
    }
    return new VanishState(version, vanished);
  }

  private static JsonElement required(JsonObject object, String field) {
    if (!object.has(field) || object.get(field).isJsonNull()) {
      throw new IllegalArgumentException("Missing field: " + field);
    }
    return object.get(field);
  }

  private static void requireExactFields(JsonObject object, Set<String> expected, String name) {
    if (!object.keySet().equals(expected)) {
      throw new IllegalArgumentException("Unexpected fields in " + name);
    }
  }

  private static UUID parseUuid(String value) {
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value)) {
        throw new IllegalArgumentException("UUID must use canonical form");
      }
      return uuid;
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("Invalid vanished UUID: " + value, failure);
    }
  }

  private static void writeAtomically(Path file, VanishState state) throws IOException {
    Path absolute = file.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null) {
      parent = Path.of(".").toAbsolutePath().normalize();
    }
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, absolute.getFileName().toString() + ".", ".tmp");
    boolean replaced = false;
    try {
      try (BufferedOutputStream output =
          new BufferedOutputStream(
              Files.newOutputStream(
                  temporary,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.DSYNC))) {
        output.write(encode(state).getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
      try {
        Files.move(
            temporary,
            absolute,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        throw new IOException("Atomic state replacement is unsupported", unsupported);
      }
      replaced = true;
    } finally {
      if (!replaced) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static String encode(VanishState state) {
    StringBuilder json = new StringBuilder(64 + state.vanished().size() * 52);
    json.append("{\"version\":").append(state.version()).append(",\"vanished\":{");
    state.vanished().stream()
        .map(UUID::toString)
        .sorted(Comparator.naturalOrder())
        .forEachOrdered(
            uuid -> {
              if (json.charAt(json.length() - 1) != '{') {
                json.append(',');
              }
              json.append('"').append(uuid).append("\":true");
            });
    return json.append("}}").toString();
  }

  private static Path preserveCorruptFile(Path file) {
    if (Files.notExists(file)) {
      return null;
    }
    String baseName = file.getFileName().toString();
    Path backup = file.resolveSibling(baseName + ".bak");
    while (Files.exists(backup)) {
      backup = file.resolveSibling(baseName + "." + UUID.randomUUID() + ".bak");
    }
    try {
      try {
        Files.move(file, backup, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(file, backup);
      }
      return backup;
    } catch (IOException moveFailure) {
      try {
        Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
      } catch (IOException copyFailure) {
        return null;
      }
    }
  }

  @FunctionalInterface
  interface FileWriter {
    void write(Path file, VanishState state) throws IOException;
  }

  static VanishStateStore forTesting(Path file, VanishState state, FileWriter writer) {
    return new VanishStateStore(file, state, true, true, writer);
  }

  /** Result of loading the state file, including corruption diagnostics and the store. */
  public static final class LoadResult {
    private final VanishStateStore store;
    private final Path backupFile;
    private final Throwable failure;

    private LoadResult(VanishStateStore store, Path backupFile, Throwable failure) {
      this.store = store;
      this.backupFile = backupFile;
      this.failure = failure;
    }

    public VanishStateStore store() {
      return store;
    }

    public VanishState snapshot() {
      return store.snapshot();
    }

    public VanishState state() {
      return store.snapshot();
    }

    public boolean enabled() {
      return store.enabled();
    }

    public boolean hasValidSnapshot() {
      return store.hasValidSnapshot();
    }

    public Path backupFile() {
      return backupFile;
    }

    public Throwable failure() {
      return failure;
    }

    public String error() {
      return failure == null ? "" : failure.getMessage();
    }
  }

  /** Result of a serialized desired-state mutation. */
  public record ChangeResult(ChangeAck ack, StateDelta delta, VanishState snapshot) {
    public ChangeResult {
      Objects.requireNonNull(ack, "ack");
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }
}
