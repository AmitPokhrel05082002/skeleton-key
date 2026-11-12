package dev.skeletonkey.persistence;

import dev.skeletonkey.domain.model.InstanceState;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for workflow instances. Updates are guarded by the optimistic {@code lockVersion}. */
public interface InstanceRepository {
    /** Stores a new instance (its lockVersion is stored as given). */
    void insert(InstanceState state, UUID startedBy);

    Optional<InstanceState> findById(UUID id);

    Optional<InstanceState> findByRecordId(UUID recordId);

    /**
     * Saves {@code state} if the stored lockVersion equals {@code state.lockVersion()}, then increments it.
     *
     * @return the saved state with the incremented lockVersion
     * @throws org.springframework.dao.OptimisticLockingFailureException when the stored version differs
     */
    InstanceState update(InstanceState state);

    /** Number of not-ended instances assigned to each given user (users with none map to 0). */
    Map<UUID, Long> openTaskCounts(Collection<UUID> userIds);
}
