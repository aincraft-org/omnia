package io.github.aincraft.vanish.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.VanishState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanishStateStoreTest {
  private static final UUID PLAYER =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @TempDir Path tempDir;

  @Test
  void missingFileLoadsEnabledVersionZeroState() {
    VanishStateStore.LoadResult loaded =
        VanishStateStore.load(tempDir.resolve("vanish-state.json"));

    assertTrue(loaded.enabled());
    assertEquals(new VanishState(0, Set.of()), loaded.store().snapshot());
  }

  @Test
  void validJsonRoundTripsExactPersistedShape() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    Files.writeString(
        file,
        "{\"version\":7,\"vanished\":{\"00000000-0000-0000-0000-000000000001\":true}}");

    VanishStateStore store = VanishStateStore.load(file).store();

    assertEquals(new VanishState(7, Set.of(PLAYER)), store.snapshot());
    assertTrue(store.enabled());
  }

  @Test
  void actualChangeIncrementsVersionAndPersists() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    VanishStateStore store = VanishStateStore.load(file).store();
    ChangeRequest request = new ChangeRequest(UUID.randomUUID(), PLAYER, true);

    VanishStateStore.ChangeResult result = store.apply(request);

    assertTrue(result.ack().accepted());
    assertEquals(1, result.ack().version());
    assertNotNull(result.delta());
    assertEquals(new VanishState(1, Set.of(PLAYER)), result.snapshot());
    assertEquals(
        "{\"version\":1,\"vanished\":{\"00000000-0000-0000-0000-000000000001\":true}}",
        Files.readString(file));
  }

  @Test
  void idempotentDesiredStateReturnsCurrentVersionWithoutDelta() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    Files.writeString(
        file,
        "{\"version\":7,\"vanished\":{\"00000000-0000-0000-0000-000000000001\":true}}");
    VanishStateStore store = VanishStateStore.load(file).store();

    VanishStateStore.ChangeResult result =
        store.apply(new ChangeRequest(UUID.randomUUID(), PLAYER, true));

    assertTrue(result.ack().accepted());
    assertEquals(7, result.ack().version());
    assertEquals(null, result.delta());
    assertEquals(
        "{\"version\":7,\"vanished\":{\"00000000-0000-0000-0000-000000000001\":true}}",
        Files.readString(file));
  }

  @Test
  void replacementLeavesNoTemporaryFile() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    VanishStateStore store = VanishStateStore.load(file).store();

    store.apply(new ChangeRequest(UUID.randomUUID(), PLAYER, true));

    try (var files = Files.list(tempDir)) {
      assertEquals(1, files.count());
    }
    assertTrue(Files.isRegularFile(file));
  }

  @Test
  void corruptFileIsBackedUpAndDisablesStoreWithoutFallbackState() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    String corrupt = "{\"version\":7,\"vanished\":{\"not-a-uuid\":true}}";
    Files.writeString(file, corrupt);

    VanishStateStore.LoadResult loaded = VanishStateStore.load(file);

    assertFalse(loaded.enabled());
    assertFalse(loaded.store().enabled());
    assertTrue(Files.notExists(file));
    try (var backups = Files.list(tempDir)) {
      Path backup = backups.filter(path -> path.getFileName().toString().endsWith(".bak")).findFirst().orElseThrow();
      assertEquals(corrupt, Files.readString(backup));
    }
  }

  @Test
  void failedWritePreservesLastValidFile() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    VanishStateStore store = VanishStateStore.load(file).store();
    store.apply(new ChangeRequest(UUID.randomUUID(), PLAYER, true));
    String valid = Files.readString(file);
    VanishStateStore failingStore =
        VanishStateStore.forTesting(
            file,
            new VanishState(1, Set.of(PLAYER)),
            (path, ignored) -> {
              throw new IOException("disk full");
            });
    VanishStateStore.ChangeResult result =
        failingStore.apply(new ChangeRequest(UUID.randomUUID(), PLAYER, false));

    assertFalse(result.ack().accepted());
    assertEquals(valid, Files.readString(file));
    assertEquals(new VanishState(1, Set.of(PLAYER)), failingStore.snapshot());
  }

  @Test
  void versionOverflowIsRejectedWithoutMutation() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    Files.writeString(file, "{\"version\":9223372036854775807,\"vanished\":{}}");
    VanishStateStore store = VanishStateStore.load(file).store();

    VanishStateStore.ChangeResult result =
        store.apply(new ChangeRequest(UUID.randomUUID(), PLAYER, true));

    assertFalse(result.ack().accepted());
    assertEquals(Long.MAX_VALUE, result.ack().version());
    assertEquals(null, result.delta());
    assertEquals(new VanishState(Long.MAX_VALUE, Set.of()), result.snapshot());
    assertEquals("{\"version\":9223372036854775807,\"vanished\":{}}", Files.readString(file));
  }

  @Test
  void unsupportedAtomicReplacementFailsClosedAndPreservesFile() throws IOException {
    Path file = tempDir.resolve("vanish-state.json");
    VanishStateStore initial = VanishStateStore.load(file).store();
    initial.apply(new ChangeRequest(UUID.randomUUID(), PLAYER, true));
    String valid = Files.readString(file);
    VanishStateStore store =
        VanishStateStore.forTesting(
            file,
            new VanishState(1, Set.of(PLAYER)),
            (ignoredPath, ignoredState) ->
                {
                  throw new java.nio.file.AtomicMoveNotSupportedException(
                      ignoredPath.toString(), file.toString(), "atomic move unavailable");
                });

    VanishStateStore.ChangeResult result =
        store.apply(new ChangeRequest(UUID.randomUUID(), PLAYER, false));

    assertFalse(result.ack().accepted());
    assertEquals(valid, Files.readString(file));
    assertEquals(new VanishState(1, Set.of(PLAYER)), store.snapshot());
  }
}
