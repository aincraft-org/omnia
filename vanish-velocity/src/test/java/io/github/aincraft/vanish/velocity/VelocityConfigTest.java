package io.github.aincraft.vanish.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VelocityConfigTest {
  @TempDir Path tempDir;

  @Test
  void blankStateFileIsRejectedBeforeResolvingDataDirectory() throws IOException {
    Files.writeString(tempDir.resolve("config.yml"), "state-file: \" \"\n");

    assertThrows(IllegalArgumentException.class, () -> VelocityConfig.from(tempDir));
  }

  @Test
  void configuredSeeUuidsAreParsed() throws IOException {
    UUID configured =
        UUID.fromString("00000000-0000-0000-0000-000000000010");
    Files.writeString(tempDir.resolve("config.yml"), "see-uuids: [" + configured + "]\n");

    assertEquals(Set.of(configured), VelocityConfig.from(tempDir).configuredSeeUuids());
  }

  @Test
  void configuredSeeUuidsYamlListIsParsed() throws IOException {
    UUID configured =
        UUID.fromString("00000000-0000-0000-0000-000000000010");
    Files.writeString(tempDir.resolve("config.yml"), "see-uuids:\n  - " + configured + "\n");

    assertEquals(Set.of(configured), VelocityConfig.from(tempDir).configuredSeeUuids());
  }

  @Test
  void shippedDefaultSeeUuidsFalseDisablesConfiguredExemptions() throws IOException {
    Files.writeString(tempDir.resolve("config.yml"), "see-uuids: false\n");

    assertEquals(Set.of(), VelocityConfig.from(tempDir).configuredSeeUuids());
  }
}
