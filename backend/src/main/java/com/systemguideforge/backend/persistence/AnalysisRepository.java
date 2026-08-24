package com.systemguideforge.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;

public interface AnalysisRepository extends JpaRepository<Analysis,String> {
    boolean existsByStatusIn(Collection<AnalysisStatus> statuses);
}
