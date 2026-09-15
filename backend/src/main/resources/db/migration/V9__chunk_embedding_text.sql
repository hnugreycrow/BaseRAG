ALTER TABLE document_chunks ADD COLUMN embedding_text text;
UPDATE document_chunks SET embedding_text = content;
ALTER TABLE document_chunks ALTER COLUMN embedding_text SET NOT NULL;
ALTER TABLE document_chunks ADD CONSTRAINT document_chunks_embedding_text_not_empty
    CHECK (length(embedding_text) > 0);
