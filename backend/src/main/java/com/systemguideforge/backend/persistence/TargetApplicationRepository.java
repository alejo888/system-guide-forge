package com.systemguideforge.backend.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface TargetApplicationRepository extends JpaRepository<TargetApplication, String> {
    @Override
    @EntityGraph(attributePaths = {"excludedRouteEntities", "approvedOpenerEntities"})
    Optional<TargetApplication> findById(String id);
    /**
     * Locks the application row until the surrounding transaction ends, then loads it like {@link #findById}, so
     * concurrent changes of one application's configuration run one after the other and each reads the state the
     * previous one committed. The lock is taken first on its own: Hibernate rejects locking the outer-joined collections
     * and would otherwise read them before locking (follow-on locking), returning the state from before the wait.
     */
    default Optional<TargetApplication> findForUpdateById(String id) {
        return lockById(id).flatMap(this::findById);
    }
    /** The id of the application, locked with {@code SELECT ... FOR UPDATE}; only meaningful inside a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application.id from TargetApplication application where application.id = :id")
    Optional<String> lockById(@Param("id") String id);
    @EntityGraph(attributePaths = {"excludedRouteEntities", "approvedOpenerEntities"})
    List<TargetApplication> findAllByOrderByNameAscIdAsc();
}
