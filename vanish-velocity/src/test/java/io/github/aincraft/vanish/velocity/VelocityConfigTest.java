package io.github.aincraft.vanish.velocity;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VelocityConfigTest {
  @TempDir Path tempDir;

  @Test
  void blankStateFileIsRejectedBeforeResolvingDataDirectory() throws IOException {
    Files.writeString(tempDir.resolve("config.yml"), "state-file: \" \"\n");

    assertThrows(IllegalArgumentException.class, () -> VelocityConfig.from(tempDir));
  }
}
