package com.systemguideforge.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentSectionRepository extends JpaRepository<DocumentSection, String> {
    List<DocumentSection> findByDocumentIdOrderByPositionAsc(String documentId);
}
