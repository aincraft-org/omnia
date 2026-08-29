package io.github.aincraft.vanish.velocity;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.SnapshotRequest;
import io.github.aincraft.vanish.common.SnapshotResponse;
import io.github.aincraft.vanish.common.StateDelta;
import io.github.aincraft.vanish.common.VanishMessages;
import io.github.aincraft.vanish.common.VanishState;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

/** Asynchronous Redis coordinator for the Velocity-owned vanish state. */
public final class RedisVelocityService implements AutoCloseable {
  private final VanishStateStore store;
  private final VelocityConfig config;
  private final Logger logger;
  private final RedisClient redis;
  private final Executor operationExecutor;
  private final ExecutorService ownedOperationExecutor;
  private final ExecutorService subscriberExecutor;
  private final boolean closeExecutors;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean();
  private final CopyOnWriteArrayList<Consumer<VanishState>> stateListeners =
      new CopyOnWriteArrayList<>();
  private volatile boolean redisAvailable;

  /** Creates the production Jedis-backed authority. */
  public RedisVelocityService(VanishStateStore store, VelocityConfig config) {
    this(store, config, Logger.getLogger(RedisVelocityService.class.getName()));
  }

  /** Creates the production authority with the supplied logger. */
  public RedisVelocityService(VanishStateStore store, VelocityConfig config, Logger logger) {
    this.store = Objects.requireNonNull(store, "store");
    this.config = Objects.requireNonNull(config, "config");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.ownedOperationExecutor =
        Executors.newSingleThreadExecutor(namedFactory("vanish-velocity-redis"));
    this.operationExecutor = ownedOperationExecutor;
    this.subscriberExecutor =
        Executors.newSingleThreadExecutor(namedFactory("vanish-velocity-subscriber"));
    this.closeExecutors = true;
    this.redis = new JedisRedisClient(config);
    this.redisAvailable = false;
  }

  /** Creates an executor-backed service around an injected Redis client for deterministic tests. */
  RedisVelocityService(VanishStateStore store, RedisClient redis, Executor operationExecutor) {
    this.store = Objects.requireNonNull(store, "store");
    this.config = null;
    this.logger = Logger.getLogger(RedisVelocityService.class.getName());
    this.redis = Objects.requireNonNull(redis, "redis");
    this.operationExecutor = Objects.requireNonNull(operationExecutor, "operationExecutor");
    this.ownedOperationExecutor = null;
    this.subscriberExecutor = null;
    this.closeExecutors = false;
    this.redisAvailable = true;
  }

  /** Starts durable-key reconciliation and the blocking Redis request subscriber. */
  public void start() {
    if (closed.get() || !running.compareAndSet(false, true)) {
      return;
    }
    rewriteDurableSnapshot()
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                logger.log(Level.WARNING, "Unable to publish the initial vanish snapshot", unwrap(failure));
              }
            });
    if (subscriberExecutor != null) {
      subscriberExecutor.execute(this::subscribeLoop);
    }
  }

  /** Returns the current valid in-memory state without performing I/O. */
  public VanishState snapshot() {
    return store.hasValidSnapshot() ? store.snapshot() : null;
  }

  /** Registers a listener for authoritative state changes and immediately supplies the current view. */
  public void addStateListener(Consumer<VanishState> listener) {
    Objects.requireNonNull(listener, "listener");
    stateListeners.add(listener);
    VanishState current = snapshot();
    if (current != null) {
      notifyListener(listener, current);
    }
  }

  /** Stops delivery to a previously registered state listener. */
  public void removeStateListener(Consumer<VanishState> listener) {
    stateListeners.remove(listener);
  }

  /** True when the store has a valid snapshot that may be published or served. */
  public boolean hasValidSnapshot() {
    return store.hasValidSnapshot();
  }

  /** True when Redis has completed a successful durable-key operation. */
  public boolean redisAvailable() {
    return redisAvailable;
  }
  /** Serializes and applies a desired state request in arrival order. */
  public CompletionStage<ChangeAck> requestChange(ChangeRequest request) {
    Objects.requireNonNull(request, "request");
    CompletableFuture<ChangeAck> result = new CompletableFuture<>();
    enqueue(
        () -> {
          if (closed.get()) {
            result.completeExceptionally(new CancellationException("Redis Velocity service is closed"));
            return;
          }
          if (!store.enabled() || !store.hasValidSnapshot()) {
            result.complete(
                new ChangeAck(
                    request.requestId(), false, store.snapshot().version(), "State store is disabled"));
            return;
          }
          if (!redisAvailable) {
            result.complete(
                new ChangeAck(
                    request.requestId(), false, store.snapshot().version(), "Redis is unavailable"));
            return;
          }
          VanishStateStore.ChangeResult change = store.apply(request);
          if (!change.ack().accepted()) {
            publishAcknowledgement(change.ack(), result);
            return;
          }
          if (change.delta() == null) {
            publishAcknowledgement(change.ack(), result);
            return;
          }
          notifyStateListeners(change.snapshot());
          try {
            redis.set(VanishMessages.SNAPSHOT_KEY, VanishMessages.encode(change.snapshot()));
            redis.publish(VanishMessages.EVENTS_CHANNEL, VanishMessages.encode(change.delta()));
            redis.publish(VanishMessages.RESPONSES_CHANNEL, VanishMessages.encode(change.ack()));
            result.complete(change.ack());
          } catch (RuntimeException failure) {
            redisAvailable = false;
            result.complete(
                new ChangeAck(
                    request.requestId(),
                    false,
                    change.snapshot().version(),
                    "Redis publication failed: " + message(failure)));
          }
        },
        result);
    return result;
  }

  private void publishAcknowledgement(ChangeAck ack, CompletableFuture<ChangeAck> result) {
    try {
      redis.publish(VanishMessages.RESPONSES_CHANNEL, VanishMessages.encode(ack));
      result.complete(ack);
    } catch (RuntimeException failure) {
      redisAvailable = false;
      result.complete(
          new ChangeAck(
              ack.requestId(), false, ack.version(), "Redis publication failed: " + message(failure)));
    }
  }

  /** Handles a request received from Paper over the Redis request channel. */
  public CompletionStage<ChangeAck> onChangeRequest(ChangeRequest request) {
    return requestChange(request);
  }

  /** Publishes a full snapshot response for a backend request. */
  public CompletionStage<Void> requestSnapshot(SnapshotRequest request) {
    Objects.requireNonNull(request, "request");
    CompletableFuture<Void> result = new CompletableFuture<>();
    enqueue(
        () -> {
          if (closed.get()) {
            result.completeExceptionally(new CancellationException("Redis Velocity service is closed"));
            return;
          }
          if (!store.enabled() || !store.hasValidSnapshot()) {
            result.completeExceptionally(new IllegalStateException("State store is disabled"));
            return;
          }
          if (!redisAvailable) {
            result.completeExceptionally(new IllegalStateException("Redis is unavailable"));
            return;
          }
          SnapshotResponse response =
              new SnapshotResponse(request.requestId(), request.backendId(), store.snapshot());
          try {
            redis.publish(VanishMessages.RESPONSES_CHANNEL, VanishMessages.encode(response));
            result.complete(null);
          } catch (RuntimeException failure) {
            redisAvailable = false;
            result.completeExceptionally(failure);
          }
        },
        result);
    return result;
  }

  /** Reads and validates the durable Redis snapshot without replacing local authority. */
  public CompletionStage<VanishState> readSnapshot() {
    CompletableFuture<VanishState> result = new CompletableFuture<>();
    enqueue(
        () -> {
          if (closed.get()) {
            result.completeExceptionally(new CancellationException("Redis Velocity service is closed"));
            return;
          }
          try {
            String encoded = redis.get(VanishMessages.SNAPSHOT_KEY);
            if (encoded == null || encoded.isBlank()) {
              throw new IllegalStateException("Redis snapshot key is missing");
            }
            VanishState remote = VanishMessages.decodeVanishState(encoded);
            redisAvailable = true;
            result.complete(remote);
          } catch (RuntimeException failure) {
            redisAvailable = false;
            result.completeExceptionally(failure);
          }
        },
        result);
    return result;
  }

  /** Rewrites the durable key after startup or a Redis reconnect. */
  public CompletionStage<Void> onRedisReconnect() {
    return rewriteDurableSnapshot();
  }

  /** Decodes a Redis request message and dispatches it without doing blocking work inline. */
  public void onMessage(String channel, String message) {
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(message, "message");
    if (!VanishMessages.REQUESTS_CHANNEL.equals(channel)) {
      return;
    }
    try {
      ChangeRequest change;
      try {
        change = VanishMessages.decodeChangeRequest(message);
      } catch (IllegalArgumentException notChange) {
        SnapshotRequest snapshot = VanishMessages.decodeSnapshotRequest(message);
        requestSnapshot(snapshot);
        return;
      }
      requestChange(change);
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "Ignoring malformed vanish Redis request", failure);
    }
  }

  /** Records a Redis disconnect; the cached state remains available but new changes are rejected. */
  public void onRedisDisconnect(Throwable failure) {
    redisAvailable = false;
    if (failure != null) {
      logger.log(Level.WARNING, "Vanish Redis disconnected", failure);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    running.set(false);
    redisAvailable = false;
    stateListeners.clear();
    try {
      redis.close();
    } catch (RuntimeException failure) {
      logger.log(Level.FINE, "Error closing Redis client", failure);
    }
    if (subscriberExecutor != null) {
      subscriberExecutor.shutdownNow();
    }
    if (closeExecutors && ownedOperationExecutor != null) {
      ownedOperationExecutor.shutdownNow();
    }
  }

  private CompletionStage<Void> rewriteDurableSnapshot() {
    CompletableFuture<Void> result = new CompletableFuture<>();
    enqueue(
        () -> {
          if (closed.get()) {
            result.completeExceptionally(new CancellationException("Redis Velocity service is closed"));
            return;
          }
          if (!store.enabled() || !store.hasValidSnapshot()) {
            redisAvailable = false;
            result.completeExceptionally(new IllegalStateException("State store is disabled"));
            return;
          }
          try {
            redis.set(VanishMessages.SNAPSHOT_KEY, VanishMessages.encode(store.snapshot()));
            redisAvailable = true;
            result.complete(null);
          } catch (RuntimeException failure) {
            redisAvailable = false;
            result.completeExceptionally(failure);
          }
        },
        result);
    return result;
  }
  private void notifyStateListeners(VanishState state) {
    for (Consumer<VanishState> listener : stateListeners) {
      notifyListener(listener, state);
    }
  }

  private void notifyListener(Consumer<VanishState> listener, VanishState state) {
    try {
      listener.accept(state);
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "Vanish state listener failed", failure);
    }
  }


  private void enqueue(Runnable operation, CompletableFuture<?> result) {
    try {
      operationExecutor.execute(operation);
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }

  private void subscribeLoop() {
    while (running.get() && !closed.get()) {
      try {
        redis.subscribe(this::onMessage, this::onRedisConnected, VanishMessages.REQUESTS_CHANNEL);
      } catch (RuntimeException failure) {
        onRedisDisconnect(failure);
        sleepBeforeReconnect();
      }
    }
  }

  void onRedisConnected() {
    if (!closed.get()) {
      onRedisReconnect()
          .whenComplete(
              (ignored, failure) -> {
                if (failure != null) {
                  logger.log(
                      Level.WARNING, "Unable to repair the Redis vanish snapshot", unwrap(failure));
                }
              });
    }
  }

  private void sleepBeforeReconnect() {
    try {
      Thread.sleep(config.retryInitialMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
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

  private static String message(Throwable failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  interface RedisClient extends AutoCloseable {
    String get(String key);

    void set(String key, String value);

    long publish(String channel, String message);

    void subscribe(BiConsumer<String, String> receiver, String... channels);
    default void subscribe(
        BiConsumer<String, String> receiver, Runnable onConnected, String... channels) {
      subscribe(receiver, channels);
    }

    @Override
    void close();
  }

  private static final class JedisRedisClient implements RedisClient {
    private final HostAndPort hostAndPort;
    private final DefaultJedisClientConfig jedisConfig;
    private final JedisPool pool;

    private JedisRedisClient(VelocityConfig config) {
      hostAndPort = new HostAndPort(config.host(), config.port());
      jedisConfig = createJedisConfig(config);
      pool = new JedisPool(hostAndPort, jedisConfig);
    }

    @Override
    public String get(String key) {
      return pool.withResourceGet(jedis -> jedis.get(key));
    }

    @Override
    public void set(String key, String value) {
      pool.withResource(jedis -> jedis.set(key, value));
    }

    @Override
    public long publish(String channel, String message) {
      return pool.withResourceGet(jedis -> jedis.publish(channel, message));
    }

    @Override
    public void subscribe(BiConsumer<String, String> receiver, String... channels) {
      subscribe(receiver, () -> {}, channels);
    }

    @Override
    public void subscribe(
        BiConsumer<String, String> receiver, Runnable onConnected, String... channels) {
      try (Jedis connection = new Jedis(hostAndPort, jedisConfig)) {
        connection.subscribe(
            new JedisPubSub() {
              @Override
              public void onSubscribe(String channel, int subscribedChannels) {
                onConnected.run();
              }

              @Override
              public void onMessage(String channel, String message) {
                receiver.accept(channel, message);
              }
            },
            channels);
      }
    }

    @Override
    public void close() {
      pool.close();
    }

    private static DefaultJedisClientConfig createJedisConfig(VelocityConfig config) {
      DefaultJedisClientConfig.Builder builder =
          DefaultJedisClientConfig.builder()
              .connectionTimeoutMillis(config.connectionTimeoutMillis())
              .socketTimeoutMillis(config.socketTimeoutMillis())
              .blockingSocketTimeoutMillis(config.blockingSocketTimeoutMillis())
              .database(config.database());
      if (!config.username().isBlank()) {
        builder.user(config.username());
      }
      if (!config.password().isBlank()) {
        builder.password(config.password());
      }
      return builder.build();
    }
  }
}
