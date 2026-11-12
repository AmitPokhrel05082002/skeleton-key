package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: skeleton-backend, Property 22: Status resolution on step entry.
 *
 * <p>**Validates: Requirements 10.13, 10.14**
 *
 * <p>Only StatusResolver is asserted here; the step-entry loop (skip and END outcome handling) is not built yet.
 * A skipped step applies no status, which at this level means a hop with null transition and null action and a
 * target without status keeps the current status.
 */
class StatusResolutionPropertyTest {

  private static Step step(String status) {
    return new Step("S", null, Step.Type.TASK, null, status, null, null, null, null, Map.of(),
        List.of(), List.of(), List.of(), List.of(), null, null, null, List.of(), null, null);
  }

  private static Action action(String status) {
    return new Action("GO", "Go", List.of("R"), null, status, null, null, null, null, null, null);
  }

  private static Transition transition(String status) {
    return new Transition("A", "B", null, null, 100, status);
  }

  /** Independent reference model: pick the first present value in precedence order. */
  private static String model(String t, String a, String s, String cur) {
    List<String> order = java.util.Arrays.asList(t, a, s, cur);
    return order.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
  }

  @Provide
  Arbitrary<String> maybeLabel() {
    return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12).injectNull(0.4);
  }

  @Provide
  Arbitrary<String> label() {
    return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
  }

  @Property(tries = 500)
  void firstNonNullWins(
      @ForAll("maybeLabel") String t,
      @ForAll("maybeLabel") String a,
      @ForAll("maybeLabel") String s,
      @ForAll("label") String cur) {
    String actual = StatusResolver.resolveStatus(transition(t), action(a), step(s), cur);
    assertEquals(model(t, a, s, cur), actual);
  }

  @Property(tries = 300)
  void absentTransitionOrActionObjectsAreSkipped(
      @ForAll("maybeLabel") String a,
      @ForAll("maybeLabel") String s,
      @ForAll("label") String cur) {
    // Fallback move (no transition) and skipped-step hop (no action) pass null arguments.
    assertEquals(model(null, a, s, cur), StatusResolver.resolveStatus(null, action(a), step(s), cur));
    assertEquals(model(null, null, s, cur), StatusResolver.resolveStatus(null, null, step(s), cur));
    assertEquals(cur, StatusResolver.resolveStatus(null, null, null, cur));
  }

  @Property(tries = 300)
  void skippedHopWithoutStatusKeepsCurrent(@ForAll("label") String cur) {
    assertEquals(cur, StatusResolver.resolveStatus(null, null, step(null), cur));
  }

  @Property(tries = 300)
  void transitionStatusAlwaysWins(
      @ForAll("label") String t,
      @ForAll("maybeLabel") String a,
      @ForAll("maybeLabel") String s,
      @ForAll("label") String cur) {
    assertEquals(t, StatusResolver.resolveStatus(transition(t), action(a), step(s), cur));
  }

  @Property(tries = 300)
  void combinedPresenceMatrix(@ForAll("presence") boolean[] p, @ForAll("label") String cur) {
    String t = p[0] ? "T" : null;
    String a = p[1] ? "A" : null;
    String s = p[2] ? "S" : null;
    assertEquals(model(t, a, s, cur), StatusResolver.resolveStatus(transition(t), action(a), step(s), cur));
  }

  @Provide
  Arbitrary<boolean[]> presence() {
    Arbitrary<Boolean> b = Arbitraries.of(true, false);
    return Combinators.combine(b, b, b).as((x, y, z) -> new boolean[] {x, y, z});
  }
}
