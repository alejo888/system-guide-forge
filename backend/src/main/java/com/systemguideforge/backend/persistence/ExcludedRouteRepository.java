package com.systemguideforge.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExcludedRouteRepository extends JpaRepository<ExcludedRoute, String> {
    List<ExcludedRoute> findByApplicationIdOrderByPath(String applicationId);
    void deleteByApplicationId(String applicationId);
}
