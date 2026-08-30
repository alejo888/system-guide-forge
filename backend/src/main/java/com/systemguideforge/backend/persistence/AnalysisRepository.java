package com.systemguideforge.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface AnalysisRepository extends JpaRepository<Analysis,String> {
    boolean existsByStatusIn(Collection<AnalysisStatus> statuses);
    List<Analysis> findByApplicationIdOrderByStartedAtDescIdDesc(String applicationId);
}
