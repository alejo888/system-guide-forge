CREATE TABLE documents (
    id VARCHAR(36) PRIMARY KEY,
    application_id VARCHAR(36) NOT NULL,
    source_analysis_id VARCHAR(36) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL,
    CONSTRAINT fk_documents_application FOREIGN KEY (application_id) REFERENCES target_applications (id),
    CONSTRAINT fk_documents_source_analysis FOREIGN KEY (source_analysis_id) REFERENCES analyses (id)
);

CREATE TABLE document_sections (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    position INTEGER NOT NULL,
    source_page_id VARCHAR(36) NOT NULL,
    screenshot_id VARCHAR(36),
    title VARCHAR(255) NOT NULL,
    content VARCHAR(10000) NOT NULL,
    CONSTRAINT fk_document_sections_document FOREIGN KEY (document_id) REFERENCES documents (id),
    CONSTRAINT fk_document_sections_source_page FOREIGN KEY (source_page_id) REFERENCES analysis_pages (id),
    CONSTRAINT fk_document_sections_screenshot FOREIGN KEY (screenshot_id) REFERENCES screenshots (id),
    CONSTRAINT uk_document_sections_position UNIQUE (document_id, position)
);

CREATE INDEX idx_documents_application_id ON documents (application_id);
CREATE INDEX idx_document_sections_document_id ON document_sections (document_id);
