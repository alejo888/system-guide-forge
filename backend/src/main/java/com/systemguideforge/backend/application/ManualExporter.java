package com.systemguideforge.backend.application;

public interface ManualExporter {
    ExportedManual export(String documentId);

    record ExportedManual(String title, byte[] bytes) {}
}
