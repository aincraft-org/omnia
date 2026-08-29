package io.github.aincraft.vanish.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.SnapshotRequest;
import io.github.aincraft.vanish.common.StateDelta;
import io.github.aincraft.vanish.common.VanishMessages;
import io.github.aincraft.vanish.common.VanishState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CloseResource"})
class RedisVelocityServiceTest {
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @TempDir Path tempDir;

  @Test
  void startupWritesDurableSnapshotAndChangePublishesSetThenDelta() {
    FakeRedis redis = new FakeRedis();
    RedisVelocityService service =
        service(redis, VanishStateStore.load(tempDir.resolve("state.json")).store());

    service.start();
    service
        .requestChange(new ChangeRequest(UUID.randomUUID(), PLAYER, true))
        .toCompletableFuture()
        .join();

    assertEquals(2, redis.operations.size());
    assertTrue(redis.operations.get(0).startsWith("set:" + VanishMessages.SNAPSHOT_KEY));
    assertTrue(redis.operations.get(1).startsWith("set:" + VanishMessages.SNAPSHOT_KEY));
    assertEquals(3, redis.published.size());
    assertEquals("snapshot_response", redis.published.get(0).type());
    assertEquals("state_delta", redis.published.get(1).type());
    assertEquals("change_ack", redis.published.get(2).type());
  }

  @Test
  void startupRewriteCompletesBeforeSubscriberConnects() throws Exception {
    FakeRedis redis = new FakeRedis();
    CountDownLatch subscribed = new CountDownLatch(1);
    AtomicReference<RedisVelocityService> serviceRef = new AtomicReference<>();
    redis.onSubscribe =
        () -> {
          subscribed.countDown();
          serviceRef.get().close();
        };
    ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();
    RedisVelocityService service =
        new RedisVelocityService(
            VanishStateStore.load(tempDir.resolve("state.json")).store(),
            redis,
            Runnable::run,
            subscriberExecutor);
    serviceRef.set(service);

    service.start();

    assertTrue(subscribed.await(1, TimeUnit.SECONDS));
    assertTrue(redis.operations.get(0).startsWith("set:" + VanishMessages.SNAPSHOT_KEY));
    assertEquals("subscribe", redis.operations.get(1));
    assertEquals("snapshot_response", redis.published.get(0).type());
    assertEquals(List.of("close-subscription", "close"), redis.lifecycle);
    service.close();
    subscriberExecutor.shutdownNow();
  }

  @Test
  void failedPublicationIsRepairedByLaterIdempotentRequest() {
    FakeRedis redis = new FakeRedis();
    VanishStateStore store = VanishStateStore.load(tempDir.resolve("state.json")).store();
    RedisVelocityService service = service(redis, store);
    service.start();
    redis.failuresBeforePublish = 1;

    ChangeAck failed =
        service
            .requestChange(new ChangeRequest(UUID.randomUUID(), PLAYER, true))
            .toCompletableFuture()
            .join();

    assertFalse(failed.accepted());
    assertEquals(new VanishState(1, Set.of(PLAYER)), service.snapshot());

    ChangeAck repaired =
        service
            .requestChange(new ChangeRequest(UUID.randomUUID(), PLAYER, true))
            .toCompletableFuture()
            .join();

    assertTrue(repaired.accepted());
    assertEquals(1, repaired.version());
    assertEquals(3, redis.operations.size());
    assertEquals(
        new VanishState(1, Set.of(PLAYER)), VanishMessages.decodeVanishState(redis.snapshot));
    assertEquals("state_delta", redis.published.get(1).type());
    assertEquals(3, redis.published.size());
    assertEquals(
        new StateDelta(1, PLAYER, true),
        VanishMessages.decodeStateDelta(redis.published.get(1).message()));
  }

  @Test
  void closeClosesSubscriptionBeforeRedisClient() {
    FakeRedis redis = new FakeRedis();
    RedisVelocityService service =
        service(redis, VanishStateStore.load(tempDir.resolve("state.json")).store());

    service.close();

    assertEquals(List.of("close-subscription", "close"), redis.lifecycle);
  }

  @Test
  void successfulConnectionCallbackRepairsSnapshotBeforeSubscriberDisconnects() {
    FakeRedis redis = new FakeRedis();
    VanishStateStore store = VanishStateStore.load(tempDir.resolve("state.json")).store();
    RedisVelocityService service = service(redis, store);

    service.start();
    redis.operations.clear();
    service.onRedisDisconnect(new IllegalStateException("connection dropped"));
    service.onRedisConnected();

    assertEquals(1, redis.operations.size());
    assertTrue(redis.operations.get(0).startsWith("set:" + VanishMessages.SNAPSHOT_KEY));
    assertTrue(service.redisAvailable());
  }

  @Test
  void idempotentDesiredStateDoesNotPublishDelta() {
    FakeRedis redis = new FakeRedis();
    VanishStateStore store = VanishStateStore.load(tempDir.resolve("state.json")).store();
    RedisVelocityService service = service(redis, store);

    service.start();
    service
        .requestChange(new ChangeRequest(UUID.randomUUID(), PLAYER, true))
        .toCompletableFuture()
        .join();
    int publishes = redis.published.size();
    var ack =
        service
            .requestChange(new ChangeRequest(UUID.randomUUID(), PLAYER, true))
            .toCompletableFuture()
            .join();

    assertTrue(ack.accepted());
    assertEquals(1, ack.version());
    assertEquals(publishes + 1, redis.published.size());
    assertEquals("change_ack", redis.published.get(redis.published.size() - 1).type());
  }

  @Test
  void RedisDownRetainsCachedMaskingAndRejectsNewChanges() {
    FakeRedis redis = new FakeRedis();
    VanishStateStore store = VanishStateStore.load(tempDir.resolve("state.json")).store();
    RedisVelocityService service = service(redis, store);
    service.start();
    service
        .requestChange(new ChangeRequest(UUID.randomUUID(), PLAYER, true))
        .toCompletableFuture()
        .join();
    service.onRedisDisconnect(new IllegalStateException("offline"));

    var ack =
        service
            .requestChange(new ChangeRequest(UUID.randomUUID(), PLAYER, false))
            .toCompletableFuture()
            .join();

    assertFalse(ack.accepted());
    assertEquals(new VanishState(1, Set.of(PLAYER)), service.snapshot());
  }

  @Test
  void corruptStoreNeverPublishesEmptySnapshot() throws Exception {
    Path file = tempDir.resolve("state.json");
    Files.writeString(file, "{\"version\":4,\"vanished\":{\"bad\":true}}");
    VanishStateStore store = VanishStateStore.load(file).store();
    FakeRedis redis = new FakeRedis();
    RedisVelocityService service = service(redis, store);

    service.start();

    assertNull(service.snapshot());
    assertTrue(redis.operations.isEmpty());
    assertTrue(redis.published.isEmpty());
  }

  @Test
  void snapshotRequestPublishesFullVersionedResponse() {
    FakeRedis redis = new FakeRedis();
    VanishStateStore store = VanishStateStore.load(tempDir.resolve("state.json")).store();
    RedisVelocityService service = service(redis, store);
    service.start();

    UUID requestId = UUID.randomUUID();
    service.requestSnapshot(new SnapshotRequest(requestId, "alpha")).toCompletableFuture().join();

    assertEquals(2, redis.published.size());
    assertEquals("snapshot_response", redis.published.get(1).type());
    assertEquals(
        requestId,
        VanishMessages.decodeSnapshotResponse(redis.published.get(1).message).requestId());
  }

  private static RedisVelocityService service(FakeRedis redis, VanishStateStore store) {
    return new RedisVelocityService(store, redis, Runnable::run);
  }

  private static final class FakeRedis implements RedisVelocityService.RedisClient {
    private final List<String> operations = new ArrayList<>();
    private final List<Published> published = new ArrayList<>();
    private final List<String> lifecycle = new ArrayList<>();
    private int failuresBeforePublish;
    private Runnable onSubscribe;
    private String snapshot;

    @Override
    public String get(String key) {
      return null;
    }

    @Override
    public void set(String key, String value) {
      operations.add("set:" + key + ":" + value);
      if (VanishMessages.SNAPSHOT_KEY.equals(key)) {
        snapshot = value;
      }
    }

    @Override
    public long publish(String channel, String message) {
      if (failuresBeforePublish > 0) {
        failuresBeforePublish--;
        throw new IllegalStateException("simulated Redis publish failure");
      }
      published.add(new Published(channel, message));
      return 1;
    }

    @Override
    public void subscribe(
        java.util.function.BiConsumer<String, String> receiver, String... channels) {}

    @Override
    public void subscribe(
        java.util.function.BiConsumer<String, String> receiver,
        Runnable connected,
        String... channels) {
      operations.add("subscribe");
      connected.run();
      if (onSubscribe != null) {
        onSubscribe.run();
      }
    }

    @Override
    public void closeSubscription() {
      lifecycle.add("close-subscription");
    }

    @Override
    public void close() {
      lifecycle.add("close");
    }
  }

  private record Published(String channel, String message) {
    private String type() {
      if (message.contains("\"type\":\"snapshot_response\"")) {
        return "snapshot_response";
      }
      if (message.contains("\"type\":\"change_ack\"")) {
        return "change_ack";
      }
      return "state_delta";
    }
  }
}
