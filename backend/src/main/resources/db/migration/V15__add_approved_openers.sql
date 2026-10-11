CREATE TABLE approved_openers (
    id VARCHAR(36) PRIMARY KEY,
    application_id VARCHAR(36) NOT NULL,
    route_pattern VARCHAR(200) NOT NULL,
    accessible_name VARCHAR(120) NOT NULL,
    CONSTRAINT fk_approved_openers_application
        FOREIGN KEY (application_id) REFERENCES target_applications (id) ON DELETE CASCADE,
    CONSTRAINT uq_approved_openers_application_route_name UNIQUE (application_id, route_pattern, accessible_name)
);

CREATE INDEX idx_approved_openers_application_id ON approved_openers (application_id);
