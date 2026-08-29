package io.github.aincraft.vanish.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.SnapshotRequest;
import io.github.aincraft.vanish.common.VanishState;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.Server;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CloseResource")
class PlayerListenerTest {
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void closeScheduler() {
    scheduler.shutdownNow();
  }

  @Test
  void deniesPreLoginImmediatelyWhenNoValidCacheExists() throws Exception {
    FakeTransport transport = new FakeTransport();
    VanishManager manager = manager(List.of());
    PlayerListener listener = listener(manager, transport);
    AsyncPlayerPreLoginEvent event =
        new AsyncPlayerPreLoginEvent(
            "new-player", InetAddress.getLoopbackAddress(), UUID.randomUUID());

    listener.onPreLogin(event);

    assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
  }

  @Test
  void appliesCachedJoinStateImmediatelyDuringRedisOutage() {
    PaperTestDoubles.FakePlayer viewer = new PaperTestDoubles.FakePlayer(VIEWER, "Viewer");
    PaperTestDoubles.FakePlayer target = new PaperTestDoubles.FakePlayer(TARGET, "Target");
    FakeTransport transport = new FakeTransport();
    transport.snapshot.completeExceptionally(new IllegalStateException("offline"));
    VanishManager manager = manager(List.of(viewer, target));
    RedisPaperService service = service(manager, transport);
    service.onStateSnapshot(new VanishState(1, Set.of(TARGET)));
    PlayerListener listener = listener(manager, transport, service);

    listener.onJoin(new PlayerJoinEvent(target.player(), ""));

    assertEquals(1, viewer.hideCalls());
  }

  private PlayerListener listener(VanishManager manager, FakeTransport transport) {
    return listener(manager, transport, service(manager, transport));
  }

  private PlayerListener listener(
      VanishManager manager, FakeTransport transport, RedisPaperService service) {
    return new PlayerListener(managerPlugin(manager), manager, service);
  }

  private RedisPaperService service(VanishManager manager, FakeTransport transport) {
    return new RedisPaperService(manager, transport, scheduler, Duration.ofSeconds(1), "test");
  }

  private VanishManager manager(List<PaperTestDoubles.FakePlayer> players) {
    Server server = PaperTestDoubles.server(players);
    Plugin plugin = PaperTestDoubles.plugin(server);
    return new VanishManager(
        plugin, () -> players.stream().map(PaperTestDoubles.FakePlayer::player).toList());
  }

  private Plugin managerPlugin(VanishManager ignored) {
    return PaperTestDoubles.plugin(PaperTestDoubles.server(List.of()));
  }

  private static final class FakeTransport implements VanishTransport {
    private final CompletableFuture<VanishState> snapshot = new CompletableFuture<>();

    @Override
    public CompletionStage<VanishState> readSnapshot() {
      return snapshot;
    }

    @Override
    public CompletionStage<ChangeAck> requestChange(ChangeRequest request) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<Void> requestSnapshot(SnapshotRequest request) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {}
  }
}
