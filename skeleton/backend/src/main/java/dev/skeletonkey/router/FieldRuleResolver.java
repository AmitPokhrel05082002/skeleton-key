package dev.skeletonkey.router;

import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Step.FieldRule;

/** Resolves the effective field rule of a field in a step (Req 11.3). */
public final class FieldRuleResolver {

  private FieldRuleResolver() {}

  /** The rule listed on the step, or READONLY when the field is not listed. */
  public static FieldRule effectiveRule(Step step, String fieldCode) {
    if (step == null || step.fields() == null) {
      return FieldRule.READONLY;
    }
    FieldRule rule = step.fields().get(fieldCode);
    return rule == null ? FieldRule.READONLY : rule;
  }
}
