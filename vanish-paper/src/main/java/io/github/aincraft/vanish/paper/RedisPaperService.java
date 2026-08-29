package io.github.aincraft.vanish.paper;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.SnapshotRequest;
import io.github.aincraft.vanish.common.SnapshotResponse;
import io.github.aincraft.vanish.common.StateDelta;
import io.github.aincraft.vanish.common.VanishMessages;
import io.github.aincraft.vanish.common.VanishState;
import java.time.Duration;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

/** Asynchronous Redis transport and versioned state reconciler for a Paper backend. */
public final class RedisPaperService implements VanishTransport {
  private final VanishManager manager;
  private final RedisConfig config;
  private final NavigableMap<Long, StateDelta> queuedDeltas = new TreeMap<>();
  private final Logger logger;
  private final VanishTransport delegate;
  private final JedisPool pool;
  private final DefaultJedisClientConfig jedisConfig;
  private final HostAndPort hostAndPort;
  private final Executor operationExecutor;
  private final ExecutorService ownedOperationExecutor;
  private final ExecutorService subscriberExecutor;
  private final ScheduledExecutorService retryExecutor;
  private final boolean closeRetryExecutor;
  private final ConcurrentMap<UUID, CompletableFuture<ChangeAck>> pendingChanges =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, CompletableFuture<SnapshotResponse>> pendingSnapshots =
      new ConcurrentHashMap<>();
  private final Object lifecycleLock = new Object();
  private final Object stateLock = new Object();
  private final CompletableFuture<Void> subscriptionReady = new CompletableFuture<>();
  private final AtomicBoolean snapshotRequestInFlight = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile boolean running;
  private volatile boolean validSnapshot;
  private volatile long retryDelayMillis;
  private volatile ScheduledFuture<?> retryTask;
  private volatile Jedis subscriber;

  /** Creates the production Jedis-backed service. */
  public RedisPaperService(JavaPlugin plugin, VanishManager manager, RedisConfig config) {
    java.util.Objects.requireNonNull(plugin, "plugin");
    this.manager = java.util.Objects.requireNonNull(manager, "manager");
    this.config = java.util.Objects.requireNonNull(config, "config");
    this.logger = plugin.getLogger();
    this.delegate = null;
    this.hostAndPort = new HostAndPort(config.host(), config.port());
    this.jedisConfig = createJedisConfig(config);
    this.pool = new JedisPool(hostAndPort, jedisConfig);
    this.ownedOperationExecutor =
        Executors.newFixedThreadPool(2, namedFactory("vanish-redis-operation"));
    this.operationExecutor = ownedOperationExecutor;
    this.subscriberExecutor =
        Executors.newSingleThreadExecutor(namedFactory("vanish-redis-subscriber"));
    this.retryExecutor =
        Executors.newSingleThreadScheduledExecutor(namedFactory("vanish-redis-retry"));
    this.closeRetryExecutor = true;
    this.retryDelayMillis = config.retryInitialMillis();
    this.requestTimeoutMillis = config.requestTimeoutMillis();
    this.backendId = config.backendId();
  }

  /**
   * Creates a deterministic, transport-injected service for unit tests and embedded callers.
   *
   * <p>The injected transport remains responsible for Redis I/O. This constructor only exercises
   * the Paper reconciliation, matching, timeout, and lifecycle behavior.
   */
  RedisPaperService(
      VanishManager manager,
      VanishTransport delegate,
      ScheduledExecutorService scheduler,
      Duration requestTimeout,
      String backendId) {
    this.manager = java.util.Objects.requireNonNull(manager, "manager");
    this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    java.util.Objects.requireNonNull(scheduler, "scheduler");
    java.util.Objects.requireNonNull(requestTimeout, "requestTimeout");
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    java.util.Objects.requireNonNull(backendId, "backendId");
    this.config = null;
    this.logger = Logger.getLogger(RedisPaperService.class.getName());
    this.pool = null;
    this.jedisConfig = null;
    this.hostAndPort = null;
    this.operationExecutor = Runnable::run;
    this.ownedOperationExecutor = null;
    this.subscriberExecutor = null;
    this.retryExecutor = scheduler;
    this.closeRetryExecutor = false;
    this.retryDelayMillis = RedisConfig.DEFAULT_RETRY_INITIAL_MILLIS;
    this.requestTimeoutMillis = requestTimeout.toMillis();
    this.backendId = backendId;
  }

  private final long requestTimeoutMillis;
  private final String backendId;

  /** Starts the subscriber and schedules the initial reconciliation. */
  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get() || running) {
        return;
      }
      running = true;
      if (delegate == null) {
        subscriberExecutor.execute(this::subscribeLoop);
      }
    }
  }

  /** True after at least one valid snapshot has been applied to the local cache. */
  public boolean hasValidState() {
    return validSnapshot;
  }

  /** Returns the latest state known by this backend without performing Redis I/O. */
  public VanishState cachedSnapshot() {
    return manager.snapshot();
  }

  /** Reconciles from the durable snapshot, falling back to a snapshot request when necessary. */
  public CompletionStage<VanishState> reconcile() {
    if (closed.get()) {
      return failedStage(new CancellationException("Redis Paper service is closed"));
    }
    CompletableFuture<VanishState> result = new CompletableFuture<>();
    readSnapshot()
        .whenComplete(
            (state, error) -> {
              if (error == null && state != null && validSnapshot) {
                resetRetryDelay();
                result.complete(state);
                return;
              }
              if (validSnapshot) {
                result.complete(manager.snapshot());
                scheduleRetry();
                return;
              }
              requestSnapshotForReconciliation()
                  .whenComplete(
                      (snapshot, snapshotError) -> {
                        if (snapshotError == null && snapshot != null && validSnapshot) {
                          resetRetryDelay();
                          result.complete(snapshot);
                        } else {
                          Throwable cause = unwrap(snapshotError == null ? error : snapshotError);
                          result.completeExceptionally(
                              cause == null
                                  ? new IllegalStateException("Redis snapshot is unavailable")
                                  : cause);
                          scheduleRetry();
                        }
                      });
            });
    return result;
  }

  /** Reconciles before allowing a player to log in; a valid cached state is safe to use. */
  public CompletionStage<VanishState> reconcileForPreLogin() {
    return reconcile();
  }

  /** Reconciles before applying join visibility and returns only after a valid state is known. */
  public CompletionStage<VanishState> reconcileForJoin() {
    return reconcile();
  }

  /** Notifies this service that a Redis connection was re-established. */
  public CompletionStage<VanishState> onRedisReconnect() {
    return reconcile();
  }

  /**
   * Test and subscriber hook indicating that both Redis channels are subscribed.
   *
   * <p>The initial durable snapshot read is intentionally not started until this signal.
   */
  void onSubscriptionReady() {
    subscriptionReady.complete(null);
    if (running && !closed.get()) {
      reconcileInBackground();
    }
  }

  /** Applies a state message received from Redis if it is not older than the local state. */
  public void onStateSnapshot(VanishState snapshot) {
    java.util.Objects.requireNonNull(snapshot, "snapshot");
    boolean gap;
    synchronized (stateLock) {
      VanishState current = manager.snapshot();
      if (snapshot.version() < current.version()) {
        return;
      }
      validSnapshot = true;
      manager.applySnapshot(snapshot);
      gap = drainQueuedDeltas();
    }
    if (gap) {
      requestSnapshotForReconciliation();
    }
  }

  /** Applies an event delta, requesting a full snapshot if readiness or contiguity is lost. */
  public void onStateDelta(StateDelta delta) {
    java.util.Objects.requireNonNull(delta, "delta");
    boolean gap;
    synchronized (stateLock) {
      long currentVersion = manager.snapshot().version();
      if (delta.version() <= currentVersion) {
        return;
      }
      if (!validSnapshot || delta.version() != currentVersion + 1) {
        queuedDeltas.putIfAbsent(delta.version(), delta);
        gap = true;
      } else {
        gap = !manager.applyDelta(delta) || drainQueuedDeltas();
      }
    }
    if (gap) {
      requestSnapshotForReconciliation();
    }
  }

  private boolean drainQueuedDeltas() {
    boolean gap = false;
    while (!queuedDeltas.isEmpty()) {
      long currentVersion = manager.snapshot().version();
      queuedDeltas.headMap(currentVersion, true).clear();
      StateDelta next = queuedDeltas.get(currentVersion + 1);
      if (next == null) {
        gap = !queuedDeltas.isEmpty();
        break;
      }
      queuedDeltas.remove(currentVersion + 1);
      if (!manager.applyDelta(next)) {
        gap = true;
        break;
      }
    }
    return gap;
  }


  /** Completes a pending change only when the acknowledgement request ID matches. */
  public void onChangeAck(ChangeAck ack) {
    java.util.Objects.requireNonNull(ack, "ack");
    CompletableFuture<ChangeAck> pending = pendingChanges.remove(ack.requestId());
    if (pending != null) {
      pending.complete(ack);
    }
  }

  /** Applies and completes a pending full snapshot response when its request ID matches. */
  public void onSnapshotResponse(SnapshotResponse response) {
    java.util.Objects.requireNonNull(response, "response");
    CompletableFuture<SnapshotResponse> pending = pendingSnapshots.remove(response.requestId());
    if (pending != null) {
      onStateSnapshot(response.state());
      pending.complete(response);
    }
  }

  @Override
  public CompletionStage<VanishState> readSnapshot() {
    if (closed.get()) {
      return failedStage(new CancellationException("Redis Paper service is closed"));
    }
    if (delegate == null && running && !subscriptionReady.isDone()) {
      return subscriptionReady.thenCompose(ignored -> readSnapshot());
    }
    if (delegate != null) {
      CompletionStage<VanishState> stage;
      try {
        stage = delegate.readSnapshot();
      } catch (RuntimeException exception) {
        return failedStage(exception);
      }
      if (stage == null) {
        return failedStage(new IllegalStateException("Transport returned no snapshot stage"));
      }
      return stage.thenApply(
          state -> {
            if (state == null) {
              throw new IllegalStateException("Transport returned no snapshot");
            }
            onStateSnapshot(state);
            return state;
          });
    }
    return CompletableFuture.<VanishState>supplyAsync(
            () ->
                pool.withResourceGet(
                    jedis -> {
                      String encoded = jedis.get(VanishMessages.SNAPSHOT_KEY);
                      if (encoded == null || encoded.isBlank()) {
                        throw new IllegalStateException("Redis snapshot key is missing");
                      }
                      return VanishMessages.decodeVanishState(encoded);
                    }),
            operationExecutor)
        .thenApply(
            state -> {
              onStateSnapshot(state);
              return state;
            });
  }

  @Override
  public CompletionStage<ChangeAck> requestChange(ChangeRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    if (closed.get()) {
      return failedStage(new CancellationException("Redis Paper service is closed"));
    }
    CompletableFuture<ChangeAck> result = new CompletableFuture<>();
    if (pendingChanges.putIfAbsent(request.requestId(), result) != null) {
      return failedStage(new IllegalArgumentException("Duplicate change request ID"));
    }
    ScheduledFuture<?> timeout =
        retryExecutor.schedule(
            () -> {
              if (pendingChanges.remove(request.requestId(), result)) {
                result.completeExceptionally(
                    new java.util.concurrent.TimeoutException("Change acknowledgement timed out"));
              }
            },
            requestTimeoutMillis,
            TimeUnit.MILLISECONDS);
    result.whenComplete((ignored, error) -> timeout.cancel(false));

    if (delegate != null) {
      try {
        CompletionStage<ChangeAck> stage = delegate.requestChange(request);
        if (stage == null) {
          onRequestFailure(request.requestId(), result, new IllegalStateException("No ack stage"));
        } else {
          stage.whenComplete(
              (ack, error) -> {
                if (error != null) {
                  onRequestFailure(request.requestId(), result, error);
                } else if (ack == null) {
                  onRequestFailure(
                      request.requestId(), result, new IllegalStateException("No acknowledgement"));
                } else {
                  onChangeAck(ack);
                }
              });
        }
      } catch (RuntimeException exception) {
        onRequestFailure(request.requestId(), result, exception);
      }
      return result;
    }

    CompletableFuture.runAsync(
            () ->
                pool.withResource(
                    jedis ->
                        jedis.publish(
                            VanishMessages.REQUESTS_CHANNEL, VanishMessages.encode(request))),
            operationExecutor)
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                onRequestFailure(request.requestId(), result, error);
              }
            });
    return result;
  }

  @Override
  public CompletionStage<Void> requestSnapshot(SnapshotRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    if (closed.get()) {
      return failedStage(new CancellationException("Redis Paper service is closed"));
    }
    if (delegate != null) {
      try {
        CompletionStage<Void> stage = delegate.requestSnapshot(request);
        return stage == null
            ? failedStage(new IllegalStateException("Transport returned no snapshot stage"))
            : stage;
      } catch (RuntimeException exception) {
        return failedStage(exception);
      }
    }

    CompletableFuture<SnapshotResponse> response = new CompletableFuture<>();
    if (pendingSnapshots.putIfAbsent(request.requestId(), response) != null) {
      return failedStage(new IllegalArgumentException("Duplicate snapshot request ID"));
    }
    ScheduledFuture<?> timeout =
        retryExecutor.schedule(
            () -> {
              if (pendingSnapshots.remove(request.requestId(), response)) {
                response.completeExceptionally(
                    new java.util.concurrent.TimeoutException("Snapshot response timed out"));
              }
            },
            requestTimeoutMillis,
            TimeUnit.MILLISECONDS);
    response.whenComplete((ignored, error) -> timeout.cancel(false));

    CompletableFuture.runAsync(
            () ->
                pool.withResource(
                    jedis ->
                        jedis.publish(
                            VanishMessages.REQUESTS_CHANNEL, VanishMessages.encode(request))),
            operationExecutor)
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                pendingSnapshots.remove(request.requestId(), response);
                response.completeExceptionally(unwrap(error));
              }
            });
    return response.thenApply(ignored -> null);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    synchronized (lifecycleLock) {
      running = false;
    }
    subscriptionReady.completeExceptionally(new CancellationException("Service closed"));
    ScheduledFuture<?> reconnect = retryTask;
    if (reconnect != null) {
      reconnect.cancel(false);
    }
    Jedis active = subscriber;
    if (active != null) {
      try {
        active.close();
      } catch (RuntimeException exception) {
        logger.log(Level.FINE, "Error closing Redis subscriber", exception);
      }
    }
    pendingChanges.forEach(
        (id, future) -> future.completeExceptionally(new CancellationException("Service closed")));
    pendingChanges.clear();
    pendingSnapshots.forEach(
        (id, future) -> future.completeExceptionally(new CancellationException("Service closed")));
    pendingSnapshots.clear();
    if (delegate != null) {
      try {
        delegate.close();
      } catch (Exception exception) {
        logger.log(Level.FINE, "Error closing injected transport", exception);
      }
      return;
    }
    subscriberExecutor.shutdownNow();
    if (ownedOperationExecutor != null) {
      ownedOperationExecutor.shutdownNow();
    }
    pool.close();
    if (closeRetryExecutor) {
      retryExecutor.shutdownNow();
    }
  }

  private final class Subscriber extends JedisPubSub {
    private int subscribedChannels;

    @Override
    public void onSubscribe(String channel, int subscribed) {
      subscribedChannels = Math.max(subscribedChannels, subscribed);
      if (subscribedChannels >= 2) {
        onSubscriptionReady();
      }
    }

    @Override
    public void onMessage(String channel, String message) {
      try {
        if (VanishMessages.EVENTS_CHANNEL.equals(channel)) {
          onStateDelta(VanishMessages.decodeStateDelta(message));
        } else if (VanishMessages.RESPONSES_CHANNEL.equals(channel)) {
          dispatchResponse(message);
        }
      } catch (RuntimeException exception) {
        logger.log(Level.WARNING, "Ignoring malformed vanish Redis message", exception);
      }
    }

    private void dispatchResponse(String message) {
      try {
        onChangeAck(VanishMessages.decodeChangeAck(message));
        return;
      } catch (IllegalArgumentException ignored) {
        // Try the other response type below.
      }
      onSnapshotResponse(VanishMessages.decodeSnapshotResponse(message));
    }
  }

  private void subscribeLoop() {
    while (running && !closed.get()) {
      boolean subscribed = false;
      try (Jedis connection = new Jedis(hostAndPort, jedisConfig)) {
        subscriber = connection;
        retryDelayMillis = config.retryInitialMillis();
        connection.subscribe(
            new Subscriber(), VanishMessages.EVENTS_CHANNEL, VanishMessages.RESPONSES_CHANNEL);
        subscribed = true;
      } catch (RuntimeException exception) {
        if (running && !closed.get()) {
          logger.log(Level.WARNING, "Redis subscriber disconnected; retrying", exception);
          sleepBeforeReconnect();
        }
      } finally {
        subscriber = null;
      }
      if (subscribed && running && !closed.get()) {
        sleepBeforeReconnect();
      }
    }
  }

  private void sleepBeforeReconnect() {
    long delay = retryDelayMillis;
    retryDelayMillis = Math.min(config.retryMaxMillis(), Math.max(delay, delay * 2));
    try {
      Thread.sleep(delay);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }


  private CompletionStage<VanishState> requestSnapshotForReconciliation() {
    if (!snapshotRequestInFlight.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(validSnapshot ? manager.snapshot() : null);
    }
    SnapshotRequest request = new SnapshotRequest(UUID.randomUUID(), backendId);
    CompletableFuture<VanishState> result = new CompletableFuture<>();
    requestSnapshot(request)
        .whenComplete(
            (ignored, error) -> {
              snapshotRequestInFlight.set(false);
              if (error != null) {
                result.completeExceptionally(unwrap(error));
                scheduleRetry();
              } else if (validSnapshot) {
                result.complete(manager.snapshot());
              } else {
                result.completeExceptionally(
                    new IllegalStateException("Snapshot response did not contain valid state"));
              }
            });
    return result;
  }

  private void reconcileInBackground() {
    if (!running || closed.get()) {
      return;
    }
    reconcile().whenComplete(
        (ignored, error) -> {
          if (error != null) {
            logger.log(Level.WARNING, "Vanish Redis reconciliation failed", unwrap(error));
          }
        });
  }

  private void scheduleRetry() {
    if (!running || closed.get()) {
      return;
    }
    long delay = retryDelayMillis;
    retryDelayMillis = Math.min(config == null ? 30_000L : config.retryMaxMillis(), delay * 2);
    ScheduledFuture<?> previous = retryTask;
    if (previous != null) {
      previous.cancel(false);
    }
    retryTask = retryExecutor.schedule(this::reconcileInBackground, delay, TimeUnit.MILLISECONDS);
  }

  private void resetRetryDelay() {
    retryDelayMillis = config == null ? RedisConfig.DEFAULT_RETRY_INITIAL_MILLIS : config.retryInitialMillis();
  }

  private void onRequestFailure(
      UUID requestId, CompletableFuture<?> result, Throwable error) {
    if (pendingChanges.remove(requestId, result)) {
      result.completeExceptionally(unwrap(error));
    }
  }

  private static DefaultJedisClientConfig createJedisConfig(RedisConfig config) {
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

  private static ThreadFactory namedFactory(String prefix) {
    AtomicInteger count = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + "-" + count.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static <T> CompletionStage<T> failedStage(Throwable error) {
    CompletableFuture<T> stage = new CompletableFuture<>();
    stage.completeExceptionally(error);
    return stage;
  }

  private static Throwable unwrap(Throwable error) {
    if (error == null) {
      return null;
    }
    Throwable cause = error;
    while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }
}
