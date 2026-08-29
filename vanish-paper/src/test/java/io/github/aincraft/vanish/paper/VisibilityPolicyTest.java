package io.github.aincraft.vanish.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VisibilityPolicyTest {
  @Test
  void hidesVanishedTargetFromViewerWithoutSeePermission() {
    assertTrue(VisibilityPolicy.mustHide(true, false));
  }

  @Test
  void doesNotHideVanishedTargetFromViewerWithSeePermission() {
    assertFalse(VisibilityPolicy.mustHide(true, true));
  }

  @Test
  void doesNotHideVisibleTarget() {
    assertFalse(VisibilityPolicy.mustHide(false, false));
  }

  @Test
  void doesNotHideViewerFromThemself() {
    assertFalse(VisibilityPolicy.mustHide(true, true));
  }
}
