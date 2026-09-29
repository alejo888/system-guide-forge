package com.systemguideforge.backend.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TargetApplicationRepository extends JpaRepository<TargetApplication, String> {
    @Override
    @EntityGraph(attributePaths = "excludedRouteEntities")
    Optional<TargetApplication> findById(String id);
    @EntityGraph(attributePaths = "excludedRouteEntities")
    List<TargetApplication> findAllByOrderByNameAscIdAsc();
}
