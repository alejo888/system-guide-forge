package com.systemguideforge.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ScreenshotRepository extends JpaRepository<Screenshot,String> {
    Optional<Screenshot> findByPageId(String pageId);
    List<Screenshot> findByPageIdOrderByIdAsc(String pageId);
}
