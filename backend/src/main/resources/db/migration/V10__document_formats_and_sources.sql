-- 历史版本均为 Markdown；默认值使已有 READY 版本无需重建索引。
ALTER TABLE document_versions ADD COLUMN format varchar(12) NOT NULL DEFAULT 'MARKDOWN';
ALTER TABLE document_versions ADD COLUMN media_type varchar(100) NOT NULL DEFAULT 'text/markdown; charset=utf-8';
ALTER TABLE document_versions ADD COLUMN file_size_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE document_versions ADD CONSTRAINT document_versions_format_check
    CHECK (format IN ('MARKDOWN', 'PDF', 'DOCX'));
ALTER TABLE document_versions ADD CONSTRAINT document_versions_file_size_check
    CHECK (file_size_bytes >= 0);

-- 新来源字段覆盖行、页和段落，旧行号列继续供历史调用方使用。
ALTER TABLE document_chunks ADD COLUMN source_unit varchar(16);
ALTER TABLE document_chunks ADD COLUMN source_start integer;
ALTER TABLE document_chunks ADD COLUMN source_end integer;
-- 历史分块的行号直接回填到通用来源范围。
UPDATE document_chunks SET source_unit = 'LINE', source_start = line_start, source_end = line_end;
ALTER TABLE document_chunks ALTER COLUMN source_unit SET NOT NULL;
ALTER TABLE document_chunks ALTER COLUMN source_start SET NOT NULL;
ALTER TABLE document_chunks ALTER COLUMN source_end SET NOT NULL;
ALTER TABLE document_chunks ADD CONSTRAINT document_chunks_source_check
    CHECK (source_unit IN ('LINE', 'PAGE', 'PARAGRAPH')
        AND source_start > 0 AND source_end >= source_start);
ALTER TABLE document_chunks ALTER COLUMN line_start DROP NOT NULL;
ALTER TABLE document_chunks ALTER COLUMN line_end DROP NOT NULL;
ALTER TABLE document_chunks ADD CONSTRAINT document_chunks_line_pair_check
    CHECK ((line_start IS NULL AND line_end IS NULL)
        OR (line_start IS NOT NULL AND line_end IS NOT NULL));
