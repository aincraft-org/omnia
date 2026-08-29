package io.github.aincraft.vanish.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.SnapshotRequest;
import io.github.aincraft.vanish.common.StateDelta;
import io.github.aincraft.vanish.common.VanishState;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisPaperServiceTest {
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void closeScheduler() {
    scheduler.shutdownNow();
  }

  @Test
  void waitsForSubscriptionBeforeReadingSnapshot() {
    FakeTransport transport = new FakeTransport();
    VanishManager manager = manager();
    RedisPaperService service = service(manager, transport, Duration.ofSeconds(1));

    service.start();

    assertEquals(0, transport.readSnapshotCalls);
    service.onSubscriptionReady();

    assertEquals(1, transport.readSnapshotCalls);
  }

  @Test
  void appliesSnapshotAfterDeltaArrivesDuringStartup() {
    FakeTransport transport = new FakeTransport();
    VanishManager manager = manager();
    RedisPaperService service = service(manager, transport, Duration.ofSeconds(1));
    CompletableFuture<VanishState> snapshot = transport.snapshot;

    service.start();
    service.onSubscriptionReady();
    service.onStateDelta(new StateDelta(1, TARGET, true));
    snapshot.complete(new VanishState(0, Set.of()));

    assertEquals(new VanishState(1, Set.of(TARGET)), manager.snapshot());
    assertTrue(manager.isVanished(TARGET));
  }

  @Test
  void staleDeltaIsIgnoredAndGapRequestsSnapshot() {
    FakeTransport transport = new FakeTransport();
    VanishManager manager = manager();
    RedisPaperService service = service(manager, transport, Duration.ofSeconds(1));

    service.onStateSnapshot(new VanishState(4, Set.of()));
    service.onStateDelta(new StateDelta(3, TARGET, true));
    service.onStateDelta(new StateDelta(6, TARGET, true));

    assertFalse(manager.isVanished(TARGET));
    assertEquals(1, transport.snapshotRequests.size());
  }

  @Test
  void reconnectStartsAnotherSnapshotReconciliation() {
    FakeTransport transport = new FakeTransport();
    VanishManager manager = manager();
    RedisPaperService service = service(manager, transport, Duration.ofSeconds(1));

    service.start();
    service.onSubscriptionReady();
    transport.snapshot.complete(new VanishState(1, Set.of()));
    service.onRedisReconnect();

    assertEquals(2, transport.readSnapshotCalls);
  }

  @Test
  void rejectionAndTimeoutNeverMutateLocalState() {
    FakeTransport transport = new FakeTransport();
    VanishManager manager = manager();
    RedisPaperService service = service(manager, transport, Duration.ofMillis(20));
    ChangeRequest request = new ChangeRequest(UUID.randomUUID(), TARGET, true);

    transport.change.complete(new ChangeAck(request.requestId(), false, 2, "denied"));
    ChangeAck rejection = service.requestChange(request).toCompletableFuture().join();

    assertFalse(rejection.accepted());
    assertFalse(manager.isVanished(TARGET));

    transport.change = new CompletableFuture<>();
    CompletionException timeout =
        org.junit.jupiter.api.Assertions.assertThrows(
            CompletionException.class, () -> service.requestChange(request).toCompletableFuture().join());
    assertNotNull(timeout.getCause());
    assertFalse(manager.isVanished(TARGET));
  }

  @Test
  void onlyMatchingAcknowledgementCompletesRequest() {
    FakeTransport transport = new FakeTransport();
    VanishManager manager = manager();
    RedisPaperService service = service(manager, transport, Duration.ofSeconds(1));
    ChangeRequest request = new ChangeRequest(UUID.randomUUID(), TARGET, true);

    CompletableFuture<ChangeAck> result = service.requestChange(request).toCompletableFuture();
    service.onChangeAck(new ChangeAck(UUID.randomUUID(), true, 1, ""));
    assertFalse(result.isDone());
    ChangeAck expected = new ChangeAck(request.requestId(), true, 1, "");
    service.onChangeAck(expected);

    assertEquals(expected, result.join());
    assertFalse(manager.isVanished(TARGET));
  }

  private RedisPaperService service(
      VanishManager manager, FakeTransport transport, Duration timeout) {
    return new RedisPaperService(manager, transport, scheduler, timeout, "test-backend");
  }

  private VanishManager manager() {
    Server server = PaperTestDoubles.server(List.of());
    Plugin plugin = PaperTestDoubles.plugin(server);
    return new VanishManager(plugin, List::of);
  }

  private static final class FakeTransport implements VanishTransport {
    private int readSnapshotCalls;
    private CompletableFuture<VanishState> snapshot = new CompletableFuture<>();
    private CompletableFuture<ChangeAck> change = new CompletableFuture<>();
    private final java.util.ArrayList<SnapshotRequest> snapshotRequests = new java.util.ArrayList<>();

    @Override
    public CompletionStage<VanishState> readSnapshot() {
      readSnapshotCalls++;
      return snapshot;
    }

    @Override
    public CompletionStage<ChangeAck> requestChange(ChangeRequest request) {
      return change;
    }

    @Override
    public CompletionStage<Void> requestSnapshot(SnapshotRequest request) {
      snapshotRequests.add(request);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {}
  }
}
