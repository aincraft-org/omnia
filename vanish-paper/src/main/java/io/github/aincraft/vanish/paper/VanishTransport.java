package io.github.aincraft.vanish.paper;

import io.github.aincraft.vanish.common.ChangeAck;
import io.github.aincraft.vanish.common.ChangeRequest;
import io.github.aincraft.vanish.common.SnapshotRequest;
import io.github.aincraft.vanish.common.VanishState;
import java.util.concurrent.CompletionStage;

/** Asynchronous authority boundary used by Paper before a real transport is installed. */
public interface VanishTransport extends AutoCloseable {
  CompletionStage<VanishState> readSnapshot();

  CompletionStage<ChangeAck> requestChange(ChangeRequest request);

  CompletionStage<Void> requestSnapshot(SnapshotRequest request);
}
