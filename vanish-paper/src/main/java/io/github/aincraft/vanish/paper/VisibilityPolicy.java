package io.github.aincraft.vanish.paper;

/** Pure decision function for whether a viewer should hide a target. */
public final class VisibilityPolicy {
  private VisibilityPolicy() {}

  /** Returns whether a vanished target must be hidden from this viewer. */
  public static boolean mustHide(boolean targetVanished, boolean viewerMaySee) {
    return targetVanished && !viewerMaySee;
  }
}
