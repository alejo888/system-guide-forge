ALTER TABLE target_applications
    ADD COLUMN max_crawl_depth INTEGER NOT NULL DEFAULT 2,
    ADD CONSTRAINT chk_target_applications_crawl_depth CHECK (max_crawl_depth BETWEEN 0 AND 5);

CREATE TABLE excluded_routes (
    id VARCHAR(36) PRIMARY KEY,
    application_id VARCHAR(36) NOT NULL,
    path VARCHAR(200) NOT NULL,
    CONSTRAINT fk_excluded_routes_application
        FOREIGN KEY (application_id) REFERENCES target_applications (id) ON DELETE CASCADE,
    CONSTRAINT uq_excluded_routes_application_path UNIQUE (application_id, path)
);

CREATE INDEX idx_excluded_routes_application_id ON excluded_routes (application_id);
