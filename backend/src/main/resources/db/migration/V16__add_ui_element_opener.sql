ALTER TABLE ui_elements ADD COLUMN opener_element_id VARCHAR(36);

ALTER TABLE ui_elements ADD CONSTRAINT fk_ui_elements_opener_element
    FOREIGN KEY (opener_element_id) REFERENCES ui_elements (id) ON DELETE CASCADE;

CREATE INDEX idx_ui_elements_opener_element_id ON ui_elements (opener_element_id);
