package io.github.aincraft.vanish.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VanishProtocolTest {
  private static final UUID PLAYER = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
  private static final UUID REQUEST = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID LAST = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

  @Test
  void snapshotsEncodeDeterministicallyAndSortUuidStrings() {
    VanishState state = new VanishState(9, Set.of(LAST, FIRST));

    String expected =
        "{\"schema\":1,\"type\":\"vanish_state\",\"version\":9,\"vanished\":["
            + "\"00000000-0000-0000-0000-000000000001\","
            + "\"ffffffff-ffff-ffff-ffff-ffffffffffff\"]}";
    assertEquals(expected, VanishMessages.encode(state));
    assertEquals(expected, VanishMessages.encode(state));
    assertEquals(state, VanishMessages.decodeVanishState(expected));
  }

  @Test
  void emptySnapshotRoundTrips() {
    VanishState state = new VanishState(0, Set.of());

    assertEquals(state, VanishMessages.decodeVanishState(VanishMessages.encode(state)));
    assertTrue(state.vanished().isEmpty());
  }

  @Test
  void everyProtocolMessageRoundTripsThroughTypedEntryPoints() {
    VanishState state = new VanishState(5, Set.of(PLAYER));
    ChangeRequest changeRequest = new ChangeRequest(REQUEST, PLAYER, true);
    StateDelta stateDelta = new StateDelta(6, PLAYER, true);
    SnapshotRequest snapshotRequest = new SnapshotRequest(REQUEST, "backend-a");
    SnapshotResponse snapshotResponse = new SnapshotResponse(REQUEST, "backend-a", state);
    ChangeAck changeAck = new ChangeAck(REQUEST, true, 6, "");

    assertEquals(changeRequest, VanishMessages.decodeChangeRequest(VanishMessages.encode(changeRequest)));
    assertEquals(stateDelta, VanishMessages.decodeStateDelta(VanishMessages.encode(stateDelta)));
    assertEquals(
        snapshotRequest,
        VanishMessages.decodeSnapshotRequest(VanishMessages.encode(snapshotRequest)));
    assertEquals(
        snapshotResponse,
        VanishMessages.decodeSnapshotResponse(VanishMessages.encode(snapshotResponse)));
    assertEquals(changeAck, VanishMessages.decodeChangeAck(VanishMessages.encode(changeAck)));
  }

  @Test
  void protocolMessagesCarrySchemaAndTypeTags() {
    String json = VanishMessages.encode(new SnapshotRequest(REQUEST, "backend-a"));

    assertTrue(json.startsWith("{\"schema\":1,\"type\":\"snapshot_request\""));
  }

  @Test
  void malformedSchemaAndTypesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> VanishMessages.decodeStateDelta("{"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VanishMessages.decodeStateDelta("{\"schema\":2,\"type\":\"state_delta\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VanishMessages.decodeStateDelta("{\"schema\":1,\"type\":\"other\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VanishMessages.decodeStateDelta("[]"));
  }

  @Test
  void stateCopiesAndFreezesTheVanishedSet() {
    Set<UUID> input = new HashSet<>();
    input.add(PLAYER);
    VanishState state = new VanishState(1, input);
    input.clear();

    assertEquals(Set.of(PLAYER), state.vanished());
    assertThrows(UnsupportedOperationException.class, () -> state.vanished().add(REQUEST));
  }

  @Test
  void recordsRejectNullRequiredFields() {
    assertThrows(NullPointerException.class, () -> new VanishState(0, null));
    assertThrows(NullPointerException.class, () -> new ChangeRequest(null, PLAYER, true));
    assertThrows(NullPointerException.class, () -> new ChangeRequest(REQUEST, null, true));
    assertThrows(NullPointerException.class, () -> new StateDelta(1, null, true));
    assertThrows(NullPointerException.class, () -> new SnapshotRequest(null, "backend-a"));
    assertThrows(NullPointerException.class, () -> new SnapshotRequest(REQUEST, null));
    assertThrows(NullPointerException.class, () -> new SnapshotResponse(null, "backend-a", new VanishState(0, Set.of())));
    assertThrows(NullPointerException.class, () -> new SnapshotResponse(REQUEST, "backend-a", null));
    assertThrows(NullPointerException.class, () -> new ChangeAck(null, true, 1, ""));
    assertThrows(NullPointerException.class, () -> new ChangeAck(REQUEST, true, 1, null));
  }

  @Test
  void versionedStateStartsEmptyAndUnready() {
    VersionedState state = new VersionedState();

    assertFalse(state.ready());
    assertFalse(state.needsSnapshot());
    assertEquals(0, state.version());
    assertEquals(Set.of(), state.vanished());
    assertEquals(new VanishState(0, Set.of()), state.snapshot());
  }

  @Test
  void snapshotEstablishesReadinessAndReplacesState() {
    VersionedState state = new VersionedState();
    VanishState snapshot = new VanishState(4, Set.of(PLAYER));

    state.applySnapshot(snapshot);

    assertTrue(state.ready());
    assertFalse(state.needsSnapshot());
    assertEquals(snapshot, state.snapshot());
    assertEquals(4, state.version());
  }

  @Test
  void contiguousDeltaUpdatesStateAndClearsSnapshotRequest() {
    VersionedState state = new VersionedState();
    state.applySnapshot(new VanishState(4, Set.of()));
    state.applyDelta(new StateDelta(5, PLAYER, true));

    assertTrue(state.applyDelta(new StateDelta(6, REQUEST, true)));
    assertFalse(state.needsSnapshot());
    assertEquals(6, state.version());
    assertEquals(Set.of(PLAYER, REQUEST), state.vanished());
  }

  @Test
  void staleDeltaIsIgnoredWithoutMutation() {
    VersionedState state = new VersionedState();
    state.applySnapshot(new VanishState(4, Set.of(PLAYER)));
    VanishState before = state.snapshot();

    assertFalse(state.applyDelta(new StateDelta(4, PLAYER, false)));
    assertEquals(before, state.snapshot());
    assertFalse(state.needsSnapshot());
  }

  @Test
  void versionGapRequestsSnapshotWithoutMutation() {
    VersionedState state = new VersionedState();
    state.applySnapshot(new VanishState(4, Set.of()));

    assertFalse(state.applyDelta(new StateDelta(6, PLAYER, true)));
    assertTrue(state.needsSnapshot());
    assertEquals(4, state.version());
    assertTrue(state.vanished().isEmpty());
  }

  @Test
  void validSnapshotAndDeltaClearNeedsSnapshot() {
    VersionedState state = new VersionedState();
    state.applySnapshot(new VanishState(4, Set.of()));
    assertFalse(state.applyDelta(new StateDelta(6, PLAYER, true)));
    assertTrue(state.needsSnapshot());

    state.applySnapshot(new VanishState(8, Set.of(REQUEST)));
    assertFalse(state.needsSnapshot());
    assertTrue(state.applyDelta(new StateDelta(9, PLAYER, true)));
    assertFalse(state.needsSnapshot());
  }
}
