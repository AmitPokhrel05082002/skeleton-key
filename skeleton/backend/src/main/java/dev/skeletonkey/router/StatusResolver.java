package dev.skeletonkey.router;

import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;

/** Status precedence on entering a non-END step (Req 10.14). */
public final class StatusResolver {

  private StatusResolver() {}

  /**
   * First non-null of: transition status, action status, target step status, else the current status.
   * Any argument may be null (a skipped-step hop has no action; a fallback move has no transition).
   */
  public static String resolveStatus(Transition transition, Action action, Step target, String currentStatus) {
    if (transition != null && transition.status() != null) {
      return transition.status();
    }
    if (action != null && action.status() != null) {
      return action.status();
    }
    if (target != null && target.status() != null) {
      return target.status();
    }
    return currentStatus;
  }
}
