package io.github.aincraft.vanish.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** JSON wire format for messages shared by the Paper and Velocity modules. */
public final class VanishMessages {
  public static final String SNAPSHOT_KEY = "vanish:state:snapshot";
  public static final String REQUESTS_CHANNEL = "vanish:state:requests";
  public static final String EVENTS_CHANNEL = "vanish:state:events";
  public static final String RESPONSES_CHANNEL = "vanish:state:responses";
  public static final int SCHEMA_VERSION = 1;

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final String FIELD_REQUEST_ID = "requestId";
  private static final String FIELD_PLAYER_ID = "playerId";
  private static final String FIELD_VANISHED = "vanished";
  private static final String FIELD_VERSION = "version";
  private static final String FIELD_BACKEND_ID = "backendId";
  private static final String FIELD_ERROR_PREFIX = "Field ";

  private VanishMessages() {}

  /** Encodes one of the common protocol records into deterministic JSON. */
  public static String encode(Object message) {
    if (message instanceof VanishState state) {
      return GSON.toJson(encodeStateEnvelope(state));
    }
    if (message instanceof ChangeRequest request) {
      JsonObject object = envelope("change_request");
      object.addProperty(FIELD_REQUEST_ID, request.requestId().toString());
      object.addProperty(FIELD_PLAYER_ID, request.playerId().toString());
      object.addProperty(FIELD_VANISHED, request.vanished());
      return GSON.toJson(object);
    }
    if (message instanceof StateDelta delta) {
      JsonObject object = envelope("state_delta");
      object.addProperty(FIELD_VERSION, delta.version());
      object.addProperty(FIELD_PLAYER_ID, delta.playerId().toString());
      object.addProperty(FIELD_VANISHED, delta.vanished());
      return GSON.toJson(object);
    }
    if (message instanceof SnapshotRequest request) {
      JsonObject object = envelope("snapshot_request");
      object.addProperty(FIELD_REQUEST_ID, request.requestId().toString());
      object.addProperty(FIELD_BACKEND_ID, request.backendId());
      return GSON.toJson(object);
    }
    if (message instanceof SnapshotResponse response) {
      JsonObject object = envelope("snapshot_response");
      object.addProperty(FIELD_REQUEST_ID, response.requestId().toString());
      object.addProperty(FIELD_BACKEND_ID, response.backendId());
      object.add("state", encodeStatePayload(response.state()));
      return GSON.toJson(object);
    }
    if (message instanceof ChangeAck ack) {
      JsonObject object = envelope("change_ack");
      object.addProperty(FIELD_REQUEST_ID, ack.requestId().toString());
      object.addProperty("accepted", ack.accepted());
      object.addProperty(FIELD_VERSION, ack.version());
      object.addProperty("error", ack.error());
      return GSON.toJson(object);
    }
    throw new IllegalArgumentException("Unsupported vanish message: " + message);
  }

  /** Decodes a full vanish-state envelope. */
  public static VanishState decodeVanishState(String json) {
    return decodeState(readEnvelope(json, "vanish_state"));
  }

  /** Decodes a desired-state change request. */
  public static ChangeRequest decodeChangeRequest(String json) {
    JsonObject object = readEnvelope(json, "change_request");
    return new ChangeRequest(
        readUuid(object, FIELD_REQUEST_ID),
        readUuid(object, FIELD_PLAYER_ID),
        readBoolean(object, FIELD_VANISHED));
  }

  /** Decodes one versioned state delta. */
  public static StateDelta decodeStateDelta(String json) {
    JsonObject object = readEnvelope(json, "state_delta");
    return new StateDelta(
        readLong(object, FIELD_VERSION),
        readUuid(object, FIELD_PLAYER_ID),
        readBoolean(object, FIELD_VANISHED));
  }

  /** Decodes a backend snapshot request. */
  public static SnapshotRequest decodeSnapshotRequest(String json) {
    JsonObject object = readEnvelope(json, "snapshot_request");
    return new SnapshotRequest(
        readUuid(object, FIELD_REQUEST_ID), readString(object, FIELD_BACKEND_ID));
  }

  /** Decodes a full snapshot response. */
  public static SnapshotResponse decodeSnapshotResponse(String json) {
    JsonObject object = readEnvelope(json, "snapshot_response");
    JsonElement stateElement = required(object, "state");
    if (!stateElement.isJsonObject()) {
      throw new IllegalArgumentException("Field state must be an object");
    }
    return new SnapshotResponse(
        readUuid(object, FIELD_REQUEST_ID),
        readString(object, FIELD_BACKEND_ID),
        decodeStatePayload(stateElement.getAsJsonObject()));
  }

  /** Decodes an acknowledgement of a state change. */
  public static ChangeAck decodeChangeAck(String json) {
    JsonObject object = readEnvelope(json, "change_ack");
    return new ChangeAck(
        readUuid(object, FIELD_REQUEST_ID),
        readBoolean(object, "accepted"),
        readLong(object, FIELD_VERSION),
        readString(object, "error"));
  }

  private static JsonObject encodeStateEnvelope(VanishState state) {
    JsonObject object = envelope("vanish_state");
    object.addProperty(FIELD_VERSION, state.version());
    object.add(FIELD_VANISHED, encodeUuidArray(state));
    return object;
  }

  private static JsonObject encodeStatePayload(VanishState state) {
    JsonObject object = new JsonObject();
    object.addProperty(FIELD_VERSION, state.version());
    object.add(FIELD_VANISHED, encodeUuidArray(state));
    return object;
  }

  private static JsonArray encodeUuidArray(VanishState state) {
    List<String> uuids =
        state.vanished().stream().map(UUID::toString).sorted(Comparator.naturalOrder()).toList();
    JsonArray array = new JsonArray(uuids.size());
    uuids.forEach(array::add);
    return array;
  }

  private static JsonObject envelope(String type) {
    JsonObject object = new JsonObject();
    object.addProperty("schema", SCHEMA_VERSION);
    object.addProperty("type", type);
    return object;
  }

  private static JsonObject readEnvelope(String json, String expectedType) {
    JsonElement element = parse(json);
    if (!element.isJsonObject()) {
      throw new IllegalArgumentException("Message must be a JSON object");
    }
    JsonObject object = element.getAsJsonObject();
    long schema = readLong(object, "schema");
    if (schema != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unknown schema: " + schema);
    }
    String type = readString(object, "type");
    if (!expectedType.equals(type)) {
      throw new IllegalArgumentException("Unknown or unexpected message type: " + type);
    }
    return object;
  }

  private static JsonElement parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("JSON must not be null");
    }
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement element = JsonParser.parseReader(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new IllegalArgumentException("Trailing JSON content");
      }
      return element;
    } catch (IOException
        | JsonParseException
        | IllegalStateException
        | NumberFormatException exception) {
      throw new IllegalArgumentException("Malformed JSON", exception);
    }
  }

  private static VanishState decodeState(JsonObject object) {
    return decodeStatePayload(object);
  }

  private static VanishState decodeStatePayload(JsonObject object) {
    JsonElement arrayElement = required(object, FIELD_VANISHED);
    if (!arrayElement.isJsonArray()) {
      throw new IllegalArgumentException(FIELD_ERROR_PREFIX + FIELD_VANISHED + " must be an array");
    }
    List<UUID> vanished = new ArrayList<>();
    for (JsonElement element : arrayElement.getAsJsonArray()) {
      if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException("Vanish UUIDs must be strings");
      }
      vanished.add(parseUuid(element.getAsString(), "vanished UUID"));
    }
    return new VanishState(readLong(object, FIELD_VERSION), java.util.Set.copyOf(vanished));
  }

  private static JsonElement required(JsonObject object, String field) {
    if (!object.has(field) || object.get(field).isJsonNull()) {
      throw new IllegalArgumentException("Missing field: " + field);
    }
    return object.get(field);
  }

  private static String readString(JsonObject object, String field) {
    JsonElement element = required(object, field);
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException(FIELD_ERROR_PREFIX + field + " must be a string");
    }
    return element.getAsString();
  }

  private static boolean readBoolean(JsonObject object, String field) {
    JsonElement element = required(object, field);
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException(FIELD_ERROR_PREFIX + field + " must be a boolean");
    }
    return element.getAsBoolean();
  }

  private static long readLong(JsonObject object, String field) {
    JsonElement element = required(object, field);
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException(FIELD_ERROR_PREFIX + field + " must be an integer");
    }
    try {
      return new BigDecimal(element.getAsString()).toBigIntegerExact().longValueExact();
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalArgumentException(
          FIELD_ERROR_PREFIX + field + " must be an integer", exception);
    }
  }

  private static UUID readUuid(JsonObject object, String field) {
    return parseUuid(readString(object, field), field);
  }

  private static UUID parseUuid(String value, String field) {
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value)) {
        throw new IllegalArgumentException(
            FIELD_ERROR_PREFIX + field + " must use a canonical UUID");
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("canonical UUID")) {
        throw exception;
      }
      throw new IllegalArgumentException(FIELD_ERROR_PREFIX + field + " must be a UUID", exception);
    }
  }
}
