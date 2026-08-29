CREATE TABLE projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255)
);

CREATE TABLE target_applications (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36),
    name VARCHAR(255),
    base_url VARCHAR(255),
    login_url VARCHAR(255),
    username_encrypted VARCHAR(255),
    password_encrypted VARCHAR(255),
    CONSTRAINT fk_target_applications_project
        FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE analyses (
    id VARCHAR(36) PRIMARY KEY,
    application_id VARCHAR(36),
    status VARCHAR(255),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    failure_message VARCHAR(2000),
    CONSTRAINT fk_analyses_application
        FOREIGN KEY (application_id) REFERENCES target_applications (id)
);

CREATE TABLE analysis_pages (
    id VARCHAR(36) PRIMARY KEY,
    analysis_id VARCHAR(36),
    url VARCHAR(255),
    title VARCHAR(255),
    CONSTRAINT fk_analysis_pages_analysis
        FOREIGN KEY (analysis_id) REFERENCES analyses (id)
);

CREATE TABLE ui_elements (
    id VARCHAR(36) PRIMARY KEY,
    page_id VARCHAR(36),
    kind VARCHAR(255),
    selector VARCHAR(255),
    accessible_name VARCHAR(255),
    action_classification VARCHAR(255),
    CONSTRAINT fk_ui_elements_page
        FOREIGN KEY (page_id) REFERENCES analysis_pages (id)
);

CREATE TABLE screenshots (
    id VARCHAR(36) PRIMARY KEY,
    page_id VARCHAR(36),
    sanitized BOOLEAN,
    content BYTEA,
    CONSTRAINT fk_screenshots_page
        FOREIGN KEY (page_id) REFERENCES analysis_pages (id)
);

CREATE INDEX idx_target_applications_project_id ON target_applications (project_id);
CREATE INDEX idx_analyses_application_id ON analyses (application_id);
CREATE INDEX idx_analysis_pages_analysis_id ON analysis_pages (analysis_id);
CREATE INDEX idx_ui_elements_page_id ON ui_elements (page_id);
CREATE INDEX idx_screenshots_page_id ON screenshots (page_id);
