package io.github.aincraft.vanish.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.SnapshotRequest;
import io.github.aincraft.vanish.common.VanishState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CloseResource"
})
class VanishCommandTest {
  private static final UUID SENDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
  private static final Command COMMAND =
      new Command("vanish") {
        @Override
        public boolean execute(
            org.bukkit.command.CommandSender sender, String label, String[] args) {
          return false;
        }
      };

  @Test
  void selfToggleRequiresUsePermission() {
    PaperTestDoubles.FakePlayer sender = new PaperTestDoubles.FakePlayer(SENDER_ID, "Sender");
    Server server = PaperTestDoubles.server(List.of(sender));
    RecordingTransport transport = new RecordingTransport(new VanishState(4, Set.of()));
    VanishCommand command = command(server, transport);

    command.onCommand(sender.player(), COMMAND, "vanish", new String[0]);

    assertEquals(0, transport.snapshotCalls);
    assertTrue(sender.messages().stream().anyMatch(message -> message.contains("permission")));
  }

  @Test
  void selfToggleReadsLatestStateAndRequestsChange() {
    PaperTestDoubles.FakePlayer sender =
        new PaperTestDoubles.FakePlayer(SENDER_ID, "Sender", Permissions.USE);
    Server server = PaperTestDoubles.server(List.of(sender));
    RecordingTransport transport = new RecordingTransport(new VanishState(4, Set.of()));
    VanishCommand command = command(server, transport);

    command.onCommand(sender.player(), COMMAND, "vanish", new String[0]);

    assertEquals(1, transport.snapshotCalls);
    assertEquals(SENDER_ID, transport.request.playerId());
    assertTrue(transport.request.vanished());
    assertTrue(sender.messages().stream().anyMatch(message -> message.contains("vanished")));
  }

  @Test
  void otherToggleRequiresOthersPermission() {
    PaperTestDoubles.FakePlayer sender =
        new PaperTestDoubles.FakePlayer(SENDER_ID, "Sender", Permissions.USE);
    PaperTestDoubles.FakePlayer target = new PaperTestDoubles.FakePlayer(TARGET_ID, "Target");
    Server server = PaperTestDoubles.server(List.of(sender, target));
    RecordingTransport transport = new RecordingTransport(new VanishState(4, Set.of()));
    VanishCommand command = command(server, transport);

    command.onCommand(sender.player(), COMMAND, "vanish", new String[] {"Target"});

    assertEquals(0, transport.snapshotCalls);
    assertTrue(sender.messages().stream().anyMatch(message -> message.contains("permission")));
  }

  @Test
  void statusRequiresAdminPermission() {
    PaperTestDoubles.FakePlayer sender = new PaperTestDoubles.FakePlayer(SENDER_ID, "Sender");
    Server server = PaperTestDoubles.server(List.of(sender));
    RecordingTransport transport = new RecordingTransport(new VanishState(4, Set.of(TARGET_ID)));
    VanishCommand command = command(server, transport);

    command.onCommand(sender.player(), COMMAND, "vanish", new String[] {"status"});

    assertEquals(0, transport.snapshotCalls);
    assertTrue(sender.messages().stream().anyMatch(message -> message.contains("permission")));
  }

  @Test
  void adminStatusReadsLatestAuthoritySnapshot() {
    PaperTestDoubles.FakePlayer sender =
        new PaperTestDoubles.FakePlayer(SENDER_ID, "Sender", Permissions.ADMIN);
    Server server = PaperTestDoubles.server(List.of(sender));
    RecordingTransport transport = new RecordingTransport(new VanishState(4, Set.of(TARGET_ID)));
    VanishCommand command = command(server, transport);

    command.onCommand(sender.player(), COMMAND, "vanish", new String[] {"status"});

    assertEquals(1, transport.snapshotCalls);
    assertTrue(sender.messages().stream().anyMatch(message -> message.contains("version 4")));
    assertTrue(
        sender.messages().stream().anyMatch(message -> message.contains(TARGET_ID.toString())));
  }

  @Test
  void targetMustBeAnOnlineExactName() {
    PaperTestDoubles.FakePlayer sender =
        new PaperTestDoubles.FakePlayer(SENDER_ID, "Sender", Permissions.OTHERS);
    Server server = PaperTestDoubles.server(List.of(sender));
    RecordingTransport transport = new RecordingTransport(new VanishState(4, Set.of()));
    VanishCommand command = command(server, transport);

    command.onCommand(sender.player(), COMMAND, "vanish", new String[] {"Missing"});

    assertEquals(0, transport.snapshotCalls);
    assertTrue(
        sender.messages().stream().anyMatch(message -> message.contains("No online player")));
  }

  private static VanishCommand command(Server server, RecordingTransport transport) {
    Plugin plugin = PaperTestDoubles.plugin(server);
    return new VanishCommand(server, plugin, () -> transport);
  }

  private static final class RecordingTransport implements VanishTransport {
    private final VanishState state;
    private int snapshotCalls;
    private ChangeRequest request;

    private RecordingTransport(VanishState state) {
      this.state = state;
    }

    @Override
    public CompletionStage<VanishState> readSnapshot() {
      snapshotCalls++;
      return CompletableFuture.completedFuture(state);
    }

    @Override
    public CompletionStage<ChangeAck> requestChange(ChangeRequest request) {
      this.request = request;
      return CompletableFuture.completedFuture(
          new ChangeAck(request.requestId(), true, state.version() + 1, ""));
    }

    @Override
    public CompletionStage<Void> requestSnapshot(SnapshotRequest request) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {}
  }
}
