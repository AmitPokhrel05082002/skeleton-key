package dev.skeletonkey.router;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skeletonkey.domain.model.Assignee;
import dev.skeletonkey.domain.model.Assignee.Strategy;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssigneeResolverTest {
    InMemoryUserRepository users;
    InMemoryInstanceRepository instances;
    InMemoryAssignmentCursorRepository cursors;
    AssigneeResolver resolver;
    UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3), inactive = new UUID(0, 4);

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        instances = new InMemoryInstanceRepository();
        cursors = new InMemoryAssignmentCursorRepository();
        resolver = new AssigneeResolver(users, instances, cursors);
        for (UUID id : List.of(a, b, c)) {
            users.insert(new UserAccount(id, "u" + id.getLeastSignificantBits(), "x", "U", true, Set.of("R")));
        }
        users.insert(new UserAccount(inactive, "off", "x", "U", false, Set.of("R")));
    }

    private AssigneeResolver.Resolution run(Strategy s, String role, String field, UUID initiator, Map<String, ?> data) {
        return resolver.resolve(new Assignee(s, role, field), "APP", "WF", "STEP", initiator, data);
    }

    private void load(UUID user, int n) {
        for (int i = 0; i < n; i++) {
            instances.insert(new InstanceState(UUID.randomUUID(), UUID.randomUUID(), "APP", 1, "WF", "S", "Open",
                    user, null, false, Instant.now(), 0, user, Instant.now()));
        }
    }

    @Test
    void roleQueueIsUnassigned() {
        var r = run(Strategy.ROLE_QUEUE, "R", null, null, Map.of());
        assertThat(r.assignee()).isEmpty();
        assertThat(r.cursorUpdate()).isEmpty();
    }

    @Test
    void leastLoadedPicksFewestThenLowestId() {
        assertThat(run(Strategy.LEAST_LOADED, "R", null, null, Map.of()).assignee()).contains(a);
        load(a, 2);
        load(b, 1);
        load(c, 1);
        assertThat(run(Strategy.LEAST_LOADED, "R", null, null, Map.of()).assignee()).contains(b);
    }

    @Test
    void roundRobinAdvancesAndWraps() {
        var first = run(Strategy.ROUND_ROBIN, "R", null, null, Map.of());
        assertThat(first.assignee()).contains(a);
        assertThat(first.cursorUpdate().get().lastUserId()).isEqualTo(a);
        assertThat(cursors.findLastUser("APP", "WF", "STEP")).isEmpty();
        cursors.apply(first.cursorUpdate().get());
        assertThat(run(Strategy.ROUND_ROBIN, "R", null, null, Map.of()).assignee()).contains(b);
        cursors.apply(new dev.skeletonkey.domain.route.CursorUpdate("APP", "WF", "STEP", c));
        assertThat(run(Strategy.ROUND_ROBIN, "R", null, null, Map.of()).assignee()).contains(a);
    }

    @Test
    void noEligibleUserIsUnassigned() {
        var r = run(Strategy.ROUND_ROBIN, "NOBODY", null, null, Map.of());
        assertThat(r.assignee()).isEmpty();
        assertThat(r.cursorUpdate()).isEmpty();
        assertThat(run(Strategy.LEAST_LOADED, "NOBODY", null, null, Map.of()).assignee()).isEmpty();
    }

    @Test
    void initiatorIsStarter() {
        assertThat(run(Strategy.INITIATOR, null, null, b, Map.of()).assignee()).contains(b);
    }

    @Test
    void fromFieldRequiresExistingActiveUser() {
        assertThat(run(Strategy.FROM_FIELD, null, "owner", null, Map.of("owner", c.toString())).assignee()).contains(c);
        assertThat(run(Strategy.FROM_FIELD, null, "owner", null, Map.of("owner", inactive.toString())).assignee()).isEmpty();
        assertThat(run(Strategy.FROM_FIELD, null, "owner", null, Map.of("owner", UUID.randomUUID().toString())).assignee()).isEmpty();
        assertThat(run(Strategy.FROM_FIELD, null, "owner", null, Map.of("owner", "junk")).assignee()).isEmpty();
        assertThat(run(Strategy.FROM_FIELD, null, "owner", null, Map.of()).assignee()).isEmpty();
    }
}
