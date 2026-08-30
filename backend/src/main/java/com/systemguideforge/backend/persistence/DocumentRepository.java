package com.systemguideforge.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {
    Optional<Document> findBySourceAnalysisId(String sourceAnalysisId);
}
