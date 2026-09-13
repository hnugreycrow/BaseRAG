ALTER TABLE document_versions DROP CONSTRAINT document_versions_status_check;
ALTER TABLE document_versions ADD CONSTRAINT document_versions_status_check
    CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED'));
