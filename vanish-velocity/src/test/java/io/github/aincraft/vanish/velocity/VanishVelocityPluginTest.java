package io.github.aincraft.vanish.velocity;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanishVelocityPluginTest {
  @TempDir Path tempDir;

  @Test
  void shutdownPreventsLaterInitializationFromStartingService() {
    VanishVelocityPlugin plugin =
        new VanishVelocityPlugin(tempDir, Logger.getLogger("vanish-test"));

    plugin.onProxyShutdown(null);
    plugin.onProxyInitialization(null);

    assertNull(plugin.redisService());
  }
}
