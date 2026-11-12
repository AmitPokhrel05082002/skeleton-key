package dev.skeletonkey.persistence.jdbc;

import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.persistence.AssignmentCursorRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssignmentCursorRepository implements AssignmentCursorRepository {
    private final JdbcClient jdbc;

    public JdbcAssignmentCursorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findLastUser(String appCode, String workflowCode, String stepCode) {
        return jdbc.sql("SELECT last_user_id FROM assignment_cursor "
                        + "WHERE app_code = :app AND workflow_code = :wf AND step_code = :step")
                .param("app", appCode).param("wf", workflowCode).param("step", stepCode)
                .query((rs, n) -> JdbcSupport.uuid(rs, "last_user_id"))
                .optional();
    }

    @Override
    public void apply(CursorUpdate u) {
        jdbc.sql("""
                INSERT INTO assignment_cursor (app_code, workflow_code, step_code, last_user_id)
                VALUES (:app, :wf, :step, :user)
                ON CONFLICT (app_code, workflow_code, step_code) DO UPDATE SET last_user_id = EXCLUDED.last_user_id
                """)
                .param("app", u.appCode()).param("wf", u.workflowCode()).param("step", u.stepCode())
                .param("user", u.lastUserId())
                .update();
    }
}
