package dev.skeletonkey.router;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skeletonkey.domain.model.Assignee;
import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 4: LEAST_LOADED and ROUND_ROBIN never choose a deactivated user, whatever the open-task counts and the
 * previous round-robin cursor (which may point at the deactivated user). Users are deactivated through
 * {@code UserRepository.update}, the same call the admin service makes.
 */
class DeactivatedNeverAssignedPropertyTest {

    record Candidate(boolean activeAfter, long openTasks) {}

    @Provide
    Arbitrary<List<Candidate>> candidates() {
        return Combinators.combine(Arbitraries.of(true, false), Arbitraries.longs().between(0, 5))
                .as(Candidate::new).list().ofMinSize(1).ofMaxSize(8);
    }

    @Property(tries = 500)
    void deactivatedUsersAreNeverChosen(@ForAll("candidates") List<Candidate> spec,
                                        @ForAll Assignee.Strategy strategy,
                                        @ForAll("cursorIndex") int cursorIndex) {
        if (strategy != Assignee.Strategy.LEAST_LOADED && strategy != Assignee.Strategy.ROUND_ROBIN) {
            return;
        }
        InMemoryUserRepository users = new InMemoryUserRepository();
        Map<UUID, Long> counts = new HashMap<>();
        List<UUID> ids = spec.stream().map(c -> UUID.randomUUID()).toList();
        for (int i = 0; i < spec.size(); i++) {
            users.insert(new UserAccount(ids.get(i), "u" + i, "h", "U" + i, true, Set.of("R")));
            counts.put(ids.get(i), spec.get(i).openTasks());
        }
        // Deactivated users have the fewest open tasks, so LEAST_LOADED would pick them if it ignored the flag.
        for (int i = 0; i < spec.size(); i++) {
            if (!spec.get(i).activeAfter()) {
                users.update(ids.get(i), null, false, null);
                counts.put(ids.get(i), -1L);
            }
        }
        InMemoryInstanceRepository instances = new InMemoryInstanceRepository() {
            @Override
            public synchronized Map<UUID, Long> openTaskCounts(Collection<UUID> userIds) {
                return userIds.stream().collect(Collectors.toMap(id -> id, id -> counts.getOrDefault(id, 0L)));
            }
        };
        InMemoryAssignmentCursorRepository cursors = new InMemoryAssignmentCursorRepository();
        if (cursorIndex >= 0) {
            cursors.apply(new CursorUpdate("APP", "WF", "STEP", ids.get(cursorIndex % ids.size())));
        }
        AssigneeResolver resolver = new AssigneeResolver(users, instances, cursors);

        Set<UUID> active = users.findActiveByRole("R").stream().map(UserAccount::id).collect(Collectors.toSet());
        for (int round = 0; round < spec.size() + 1; round++) {
            AssigneeResolver.Resolution r = resolver.resolve(new Assignee(strategy, "R", null), "APP", "WF", "STEP",
                    null, Map.of());
            if (active.isEmpty()) {
                assertThat(r.assignee()).isEmpty();
            } else {
                assertThat(r.assignee()).isPresent();
                assertThat(active).contains(r.assignee().get());
            }
            r.cursorUpdate().ifPresent(cursors::apply);
        }
    }

    @Provide
    Arbitrary<Integer> cursorIndex() {
        return Arbitraries.integers().between(-1, 7);
    }
}
